import static org.octopusden.octopus.escrow.BuildSystem.*

"ee-component" {
    componentDisplayName = "EE Component"
    componentOwner = "EE Component Owner"
    releaseManager = "EE Component Release Manager"
    groupId = "corp.domain"
    vcsUrl = "https://github.com/octopusden/octopus-teamcity-automation.git"
    jira {
        projectKey = 'EE'
    }
}

"ie-component" {
    componentDisplayName = "IE Component"
    componentOwner = "IE Component Owner"
    releaseManager = "IE Component Release Manager"
    groupId = "corp.domain"
    vcsUrl = "ssh://git@git.domain.corp/ie/ie-component.git"
    jira {
        projectKey = 'IE'
    }
    distribution {
        explicit = false
        external = true
    }
}

"ei-component" {
    componentDisplayName = "EI Component"
    componentOwner = "EI Component Owner"
    releaseManager = "EI Component Release Manager"
    groupId = "corp.domain"
    vcsUrl = "ssh://git@git.domain.corp/ei/ei-component.git"
    jira {
        projectKey = 'EI'
    }
    distribution {
        explicit = true
        external = false
    }
}

"ii-component" {
    componentDisplayName = "II Component"
    componentOwner = "II Component Owner"
    releaseManager = "II Component Release Manager"
    groupId = "corp.domain"
    vcsUrl = "ssh://git@git.domain.corp/ii/ii-component.git"
    jira {
        projectKey = 'II'
    }
    distribution {
        explicit = false
        external = false
    }
}

"maven-component" {
    componentDisplayName = "Maven component"
    componentOwner = "Maven component Owner"
    releaseManager = "Maven component Manager"
    groupId = "corp.domain"
    vcsUrl = "ssh://git@git.domain.corp/ee/maven-component.git"
    jira {
        projectKey = 'BUILDSYS'
    }
}

"gradle-component" {
    buildSystem = GRADLE
    componentDisplayName = "Gradle component"
    componentOwner = "Gradle component Owner"
    releaseManager = "Gradle component Manager"
    groupId = "corp.domain"
    vcsUrl = "ssh://git@git.domain.corp/ee/gradle-component.git"
    jira {
        projectKey = 'BUILDSYS'
    }
}

"provided-component" {
    buildSystem = PROVIDED
    componentDisplayName = "Provided component"
    componentOwner = "Provided component Owner"
    releaseManager = "Provided component Manager"
    groupId = "corp.domain"
    vcsUrl = "ssh://git@git.domain.corp/ee/provided-component.git"
    jira {
        projectKey = 'BUILDSYS'
    }
}

"not-supported-component" {
    buildSystem = ESCROW_NOT_SUPPORTED
    componentDisplayName = "Not supported component"
    componentOwner = "Not supported component Owner"
    releaseManager = "Not supported component Manager"
    groupId = "corp.domain"
    vcsUrl = "ssh://git@git.domain.corp/ee/not-supported-component.git"
    jira {
        projectKey = 'BUILDSYS'
    }
}

"default-jdk-component" {
    componentDisplayName = "Default JDK component"
    componentOwner = "Default JDK component Owner"
    releaseManager = "Default JDK component Manager"
    groupId = "corp.domain"
    vcsUrl = "ssh://git@git.domain.corp/ee/default-jdk-component.git"
    jira {
        projectKey = 'JDKVER'
    }
}

"custom-jdk-component" {
    componentDisplayName = "Custom JDK component"
    componentOwner = "Custom JDK component Owner"
    releaseManager = "Custom JDK component Manager"
    groupId = "corp.domain"
    vcsUrl = "ssh://git@git.domain.corp/ee/custom-jdk-component.git"
    jira {
        projectKey = 'JDKVER'
    }
    build {
        javaVersion = "11"
    }
}