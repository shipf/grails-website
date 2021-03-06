package org.grails.plugin

import groovyx.net.http.HTTPBuilder
import org.grails.auth.User
import org.springframework.context.ApplicationEvent
import org.springframework.context.ApplicationListener
import org.springframework.transaction.annotation.Transactional

/**
 * Spring event listener that picks up updates to plugins and synchronises
 * the changes with the database. This involves accessing the repository
 * where the plugin was published and reading the POM and plugin descriptor.
 */
class PluginUpdateService implements ApplicationListener<PluginUpdateEvent> {
    static transactional = false

    private static final DEFAULT_REPOSITORIES = [
            "http://plugins.grails.org",
            "http://repo.grails.org/grails/plugins/",
            "http://repo.grails.org/grails/core/",
            "http://svn.codehaus.org/grails/trunk/grails-plugins",
            "http://repo1.maven.org/maven2/" ]

    protected int twitterLimit = 140

    def shortenService
    def twitterService
    def mailService
    def grailsApplication
    def pluginService
    def searchableService
    def wikiPageService

    /**
     * <p>Triggered whenever something publishes a plugin update event to the Spring
     * application context.</p>
     *
     * <p>Note: The @Transactional annotation is used due to a bug in the Spring
     * Events plugin - http://jira.grails.org/browse/GPSPRINGEVENTS-2 </p>
     */
    @Transactional
    void onApplicationEvent(PluginUpdateEvent event) {
        log.info "Updating information for plugin ${event.name}, version ${event.version}${event.snapshot ? ' (snapshot)' : ''}"

        // Check that the given repository URL is valid.
        def baseUrl
        try {
            baseUrl = event.repoUrl.toURL()
        }
        catch (MalformedURLException ex) {
            // If the repository URL is invalid, there's no point processing
            // this plugin update.
            log.error "Invalid repository URL provided with plugin '${event.name}': ${event.repoUrl}"
            return
        }

        // Rectify the URL if it doesn't have a trailing slash.
        if (!baseUrl.path.endsWith('/')) {
            baseUrl = new URL(baseUrl.toString() + '/')
        }

        log.debug "Base repository URL is ${baseUrl}"

        // We either need to create a new Plugin instance or update the
        // existing one. Since we already have the version, we can deal
        // with that too.
        def plugin = fetchOrCreatePluginInstance(event.name, event.version)
        def isNewVersion = !plugin.id || plugin.currentRelease != event.version
        if (!event.snapshot) {
            plugin.currentRelease = event.version
        }

        // We may be looking at either a Maven or a Subversion repository,
        // both of which have different directory structures. Here we test
        // which type of repository we have.
        def mavenUrl = new URL(baseUrl, "${event.group.replace('.', '/')}/${event.name}/${event.version}/")
        def downloadFilename = "${event.name}-${event.version}.zip"
        try {
            log.debug "Trying Maven URL: ${mavenUrl}"
            mavenUrl.text
            baseUrl = mavenUrl
        }
        catch (FileNotFoundException ex) {
            // 404 on the Maven URL, so use a Subversion repository URL instead.
            baseUrl = new URL(baseUrl, "grails-${event.name}/tags/RELEASE_${event.version?.replace('.', '_')}/")
            downloadFilename = "grails-" + downloadFilename
        }

        log.debug "Fetching plugin information from ${baseUrl}"

        // Pull in the POM and parse it.
        def parser = new XmlSlurper()
        def pomUrl = new URL(baseUrl, "${event.name}-${event.version}.pom")
        def xml = null
        pomUrl.withReader("UTF-8") { reader ->
            xml = parser.parse(reader)
        }
        
        // Update the Plugin instance with the information from the POM.
        plugin.groupId = xml.groupId.text()
        plugin.title = xml.name.text()
        plugin.summary = xml.description.text()
        plugin.documentationUrl = xml.url.text()
        plugin.author = xml.developers.developer[0].name.text()
        plugin.authorEmail = xml.developers.developer[0].email.text()
        plugin.organization = xml.organization.name.text()
        plugin.organizationUrl = xml.organization.url.text()
        plugin.scmUrl = xml.scm.url.text()
        plugin.issuesUrl = xml.issueManagement.url.text()

        addLicenses plugin, xml.licenses

        // Now do the same with the XML plugin descriptor.
        def descUrl = new URL(baseUrl, "${event.name}-${event.version}-plugin.xml")
        descUrl.withReader("UTF-8") { reader ->
            xml = parser.parse(reader)
        }

        plugin.grailsVersion = xml.@grailsVersion.text()

        // Fetch any custom repositories that may be needed by this plugin.
        def customRepoUrls = xml.repositories.repository.@url*.text().findAll { !(it in DEFAULT_REPOSITORIES) }
        addCustomRepositories plugin, customRepoUrls

        // Set the download URL for the plugin to the appropriate binary in the
        // repository, whether it be a Maven or Subversion one.
        plugin.downloadUrl = baseUrl.toURI().resolve(downloadFilename)

        if (log.debugEnabled) {
            log.debug """\
                Updated plugin info:
                  name         = ${plugin.name}
                  version      = ${plugin.currentRelease}
                  groupId      = ${plugin.groupId}
                  title        = ${plugin.title}
                  docs URL     = ${plugin.documentationUrl}
                  author name  = ${plugin.author}
                  author email = ${plugin.authorEmail}
                """.stripIndent()
        }

        pluginService.savePlugin(plugin, true)

        // Assuming the instance saved OK, we can announce the release if it's
        // a new version.
        if (isNewVersion && !event.snapshot) {
            plugin.lastReleased = new Date()
            announceRelease(plugin)
        }
        else log.info "Not a new plugin release - won't tweet"
    }

    /**
     * Fetches the plugin instance for the given name from the database
     * and returns it. If the plugin isn't in the database yet, this
     * methods creates a new instance and returns that. Note that the
     * new instance is not saved and only has the name set.
     * @param pluginName The name of the plugin.
     * @param version If the plugin needs to be created, it's current version
     * is set to this value.
     */
    @Transactional
    Plugin fetchOrCreatePluginInstance(String pluginName, String version) {
        def plugin = Plugin.findByName(pluginName)
        if (!plugin) {
            log.debug "Creating new plugin instance for $pluginName $version"
            plugin = new Plugin(name: pluginName, currentRelease: version, downloadUrl: "not provided")

            pluginService.initNewPlugin(plugin, User.findByLogin("admin"))
        }

        return plugin
    }
    
    void announceRelease(plugin, version = null) {
        def pluginUrl = siteBaseUrl + "plugin/${plugin.name}"
        announceOnPluginForum(plugin, version, pluginUrl)
        tweetRelease(plugin, version, pluginUrl)
    }
    
    /**
     * Sends a tweet to @grailsplugins with details of the new release.
     * @param plugin A plugin instance with 'name', 'title' and 'currentRelease'
     * properties.
     */
    void tweetRelease(plugin, version, url) {
        def msg = "${plugin.title} ${version ?: plugin.currentRelease} released: "

        // Check that the message with standard URL does not exceed the
        // Twitter length limit.
        if (exceedsTwitterLimit(msg, url)) url = shortenUrl(url)

        // If the message length is still over the Twitter length, we must summarize
        // the message.
        if (exceedsTwitterLimit(msg, url)) msg = summarize(msg, twitterLimit - url.size())

        log.info "Tweeting the plugin release"
        twitterService.updateStatus(msg + url)
    }
    
    void announceOnPluginForum(plugin, version, url) {
        def mailConfig = grailsApplication.config.plugins.forum.mail
        def toAddress = mailConfig.to
        def fromAddress = mailConfig.from
        
        mailService.sendMail {
            to toAddress
            from fromAddress
            subject "${plugin.title} ${version ?: plugin.currentRelease} released"
            html view: "/mail/pluginRelease", model: [plugin: plugin, version: version, url: url]
        }
    }

    protected addLicenses(plugin, pomLicensesXml) {
        for (license in pomLicensesXml.license) {
            def l = License.findOrSaveWhere(name: license.name.text(), url: license.url.text()).save()
            if (!l.hasErrors()) plugin.addToLicenses(l)
            else {
                log.warn "Invalid license declared for plugin '${plugin.name}': " +
                        "${license.name.text()}(${license.url.text()})"
            }
        }
    }

    protected addCustomRepositories(plugin, repoUrls) {
        // No need to do anything if there the custom repositories
        // haven't changed.
        if (repoUrls == plugin.mavenRepositories) return

        // Take the simple approach: clear the list and re-add
        // all declared URLs.
        if (plugin.mavenRepositories == null) plugin.mavenRepositories = []

        plugin.mavenRepositories.clear()
        plugin.mavenRepositories.addAll repoUrls
    }

    private getSiteBaseUrl() {
        return normalize(grailsApplication.config?.grails?.serverURL ?: 'http://localhost:8080/')
    }

    private normalize(url) {
        return url.endsWith('/') ? url : url + '/'
    }

    private shortenUrl(url) {
        return shortenService.shortenUrl(url)
    }

    private exceedsTwitterLimit(Object[] strs) {
        return strs*.size().sum() > twitterLimit
    }

    private summarize(str, limit) {
        def chopPoint = limit.intdiv(2) - 2
        return str[0..<chopPoint] + "..." + str[(-chopPoint)..-1]
    }
}
