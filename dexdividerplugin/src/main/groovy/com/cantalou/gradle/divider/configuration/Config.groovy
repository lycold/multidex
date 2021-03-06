package com.cantalou.gradle.divider.configuration

import com.cantalou.gradle.divider.extension.DividerConfigExtension
import org.gradle.api.Project

public class Config {

    /**
     * divider-plugin.properties
     */
    private static final String DEFAULT_CONFIG_FILE = "divider-plugin.properties"

    int totalMethodCount = 0

    int dexMethodCount = 0

    int dexCount = 0

    Project project

    Properties prop

    File configFile

    boolean inited

    static Config instance

    public static Config getInstance(Project project) {

        if (instance == null) {
            instance = new Config(project)
        }

        if (!instance.inited) {
            instance.parse()
        }

        instance
    }

    Config(Project project) {

        this.project = project
        prop = new Properties()
        def configFileName
        DividerConfigExtension ext = project.extensions.dividerConfig
        if (ext == null) {
            configFileName = Config.DEFAULT_CONFIG_FILE
        } else {
            configFileName = ext.configFile
        }
        configFile = new File(project.getRootDir(), configFileName)
    }

    public void parse() {

        if (!configFile.exists()) {
            project.println "File ${configFile} not found"
            return
        }

        configFile.withReader("UTF-8") {
            prop.load(it)
        }

        totalMethodCount = prop.getProperty("totalMethodCount") as int
        dexMethodCount = prop.getProperty("dexMethodCount") as int
        dexCount = prop.getProperty("dexCount") as int

        inited = true
    }

    public void save() {
        prop.setProperty("totalMethodCount", Integer.toString(totalMethodCount))
        prop.setProperty("dexMethodCount", Integer.toString(dexMethodCount))
        prop.setProperty("dexCount", Integer.toString(dexCount))
        configFile.withWriter("UTF-8") {
            prop.store(it, "UTF-8")
        }
    }
}