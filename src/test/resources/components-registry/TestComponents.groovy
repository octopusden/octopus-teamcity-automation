import static org.octopusden.octopus.escrow.BuildSystem.*

"ee-component" {
    componentDisplayName = "EE Component"
    componentOwner = "admin"
    releaseManager = "admin"
    groupId = "corp.domain"
    vcsUrl = "https://github.com/octopusden/octopus-teamcity-automation.git"
    jira {
        projectKey = 'EE'
    }
}

"ie-component" {
    componentDisplayName = "IE Component"
    componentOwner = "admin"
    releaseManager = "admin"
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
    componentOwner = "admin"
    releaseManager = "admin"
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
    componentOwner = "admin"
    releaseManager = "admin"
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
    componentOwner = "admin"
    releaseManager = "admin"
    groupId = "corp.domain"
    vcsUrl = "ssh://git@git.domain.corp/ee/maven-component.git"
    jira {
        projectKey = 'BUILDSYS'
    }
}

"gradle-component" {
    buildSystem = GRADLE
    componentDisplayName = "Gradle component"
    componentOwner = "admin"
    releaseManager = "admin"
    groupId = "corp.domain"
    vcsUrl = "ssh://git@git.domain.corp/ee/gradle-component.git"
    jira {
        projectKey = 'BUILDSYS'
    }
}

"provided-component" {
    buildSystem = PROVIDED
    componentDisplayName = "Provided component"
    componentOwner = "admin"
    releaseManager = "admin"
    groupId = "corp.domain"
    vcsUrl = "ssh://git@git.domain.corp/ee/provided-component.git"
    jira {
        projectKey = 'BUILDSYS'
    }
}

"in-container-component" {
    buildSystem = IN_CONTAINER
    componentDisplayName = "In container component"
    componentOwner = "admin"
    releaseManager = "admin"
    groupId = "corp.domain"
    vcsUrl = "ssh://git@git.domain.corp/ee/in-container-component.git"
    jira {
        projectKey = 'BUILDSYS'
    }
}

"not-supported-component" {
    buildSystem = ESCROW_NOT_SUPPORTED
    componentDisplayName = "Not supported component"
    componentOwner = "admin"
    releaseManager = "admin"
    groupId = "corp.domain"
    vcsUrl = "ssh://git@git.domain.corp/ee/not-supported-component.git"
    jira {
        projectKey = 'BUILDSYS'
    }
}

"default-jdk-component" {
    componentDisplayName = "Default JDK component"
    componentOwner = "admin"
    releaseManager = "admin"
    groupId = "corp.domain"
    vcsUrl = "ssh://git@git.domain.corp/ee/default-jdk-component.git"
    jira {
        projectKey = 'JDKVER'
    }
}

"custom-jdk-component" {
    componentDisplayName = "Custom JDK component"
    componentOwner = "admin"
    releaseManager = "admin"
    groupId = "corp.domain"
    vcsUrl = "ssh://git@git.domain.corp/ee/custom-jdk-component.git"
    jira {
        projectKey = 'JDKVER'
    }
    build {
        javaVersion = "11"
    }
}