-agent-experimental. # Embabel Agent Framework Experimental

<img align="left" src="https://github.com/embabel/embabel-agent/blob/main/embabel-agent-api/images/315px-Meister_der_Weltenchronik_001.jpg?raw=true" width="180">

[![Docs](https://img.shields.io/badge/docs-live-brightgreen)](https://docs.embabel.com/embabel-agent/guide/0.1.2-SNAPSHOT/)
![Build](https://github.com/embabel/embabel-agent/actions/workflows/maven.yml/badge.svg)
[![YourKit](https://img.shields.io/badge/Profiling-YourKit-blue)](https://www.yourkit.com/)
[![JProfiler](https://img.shields.io/badge/Profiled%20with-JProfiler-blue)](https://www.ej-technologies.com/products/jprofiler/overview.html)
[![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=embabel_embabel-agent&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=embabel_embabel-agent)
[![Discord](https://img.shields.io/discord/1277751399261798401?logo=discord)](https://discord.gg/t6bjkyj93q)

[//]: # ([![Quality Gate Status]&#40;https://sonarcloud.io/api/project_badges/measure?project=embabel_embabel-agent&metric=alert_status&token=d275d89d09961c114b8317a4796f84faf509691c&#41;]&#40;https://sonarcloud.io/summary/new_code?id=embabel_embabel-agent&#41;)

[//]: # ([![Bugs]&#40;https://sonarcloud.io/api/project_badges/measure?project=embabel_embabel-agent&metric=bugs&#41;]&#40;https://sonarcloud.io/summary/new_code?id=embabel_embabel-agent&#41;)
![Kotlin](https://img.shields.io/badge/kotlin-%237F52FF.svg?style=for-the-badge&logo=kotlin&logoColor=white)
![Java](https://img.shields.io/badge/java-%23ED8B00.svg?style=for-the-badge&logo=openjdk&logoColor=white)
![Spring](https://img.shields.io/badge/spring-%236DB33F.svg?style=for-the-badge&logo=spring&logoColor=white)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-6DB33F.svg?style=for-the-badge&logo=Spring-Boot&logoColor=white)
![Apache Tomcat](https://img.shields.io/badge/apache%20tomcat-%23F8DC75.svg?style=for-the-badge&logo=apache-tomcat&logoColor=black)
![Apache Maven](https://img.shields.io/badge/Apache%20Maven-C71A36?style=for-the-badge&logo=Apache%20Maven&logoColor=white)
![JUnit](https://img.shields.io/badge/JUnit5-25A162.svg?style=for-the-badge&logo=JUnit5&logoColor=white)
![ChatGPT](https://img.shields.io/badge/chatGPT-74aa9c?style=for-the-badge&logo=openai&logoColor=white)
![Jinja](https://img.shields.io/badge/jinja-white.svg?style=for-the-badge&logo=jinja&logoColor=black)
![JSON](https://img.shields.io/badge/JSON-000?logo=json&logoColor=fff)
![GitHub Actions](https://img.shields.io/badge/github%20actions-%232671E5.svg?style=for-the-badge&logo=githubactions&logoColor=white)
![SonarQube](https://img.shields.io/badge/SonarQube-black?style=for-the-badge&logo=sonarqube&logoColor=4E9BCD)
![Docker](https://img.shields.io/badge/docker-%230db7ed.svg?style=for-the-badge&logo=docker&logoColor=white)
![IntelliJ IDEA](https://img.shields.io/badge/IntelliJIDEA-000000.svg?style=for-the-badge&logo=intellij-idea&logoColor=white)
[![License](https://img.shields.io/github/license/embabel/embabel-agent?style=for-the-badge&logo=apache&color=brightgreen)](https://www.apache.org/licenses/LICENSE-2.0)
[![Commits](https://img.shields.io/github/commit-activity/m/embabel/embabel-agent.svg?label=commits&style=for-the-badge&logo=git&logoColor=white)](https://github.com/embabel/embabel-agent/pulse)

&nbsp;&nbsp;&nbsp;&nbsp;

## Overview

The **Embabel Agent Experimental** repository contains modules that are under active development and evaluation. These modules have been relocated from the main [embabel-agent](https://github.com/embabel/embabel-agent) repository as they represent innovative features and capabilities that are still maturing.

This repository as an **incubation lab** for new agent capabilities. Modules here are fully functional but may undergo API changes, refinements, or architectural improvements based on community feedback and production experience.

### Purpose

This repository serves as:

- **Innovation Space**: A dedicated environment for developing cutting-edge agent capabilities without impacting the stability of the core framework
- **Evaluation Ground**: A place where modules can be thoroughly tested, refined, and validated in real-world scenarios
- **Graduation Pipeline**: A structured path for promoting experimental features into the main framework once they reach production maturity

### Promotion Process

Experimental modules undergo a rigorous evaluation process before being promoted to the main framework:

1. **Active Development**: Module is actively developed and tested in the experimental repository
2. **Community Feedback**: Users provide feedback on API design, functionality, and use cases
3. **Stability Assessment**: The module demonstrates API stability, comprehensive testing, and production readiness
4. **Promotion Decision**: Based on maturity metrics and strategic alignment, the module is promoted to the main [embabel-agent](https://github.com/embabel/embabel-agent) repository

## Experimental Modules

The following modules are currently in the experimental repository:

| Module | Description | Status |
|--------|-------------|--------|
| **embabel-agent-eval** | Agent evaluation and benchmarking capabilities | Active Development |
| **embabel-agent-remote** | Remote agent execution and distributed processing | Active Development |
| **embabel-agent-discord** | Discord integration for agent interactions | Active Development |
| **embabel-agent-spec** | Specification and contract testing utilities | Active Development |
| **embabel-agent-skills** | Advanced skills and capability extensions | Active Development |

## Using Experimental Modules

### Adding the BOM (Bill of Materials)

The **embabel-agent-experimental-bom** provides centralized dependency management for all experimental modules, ensuring version consistency across your project - similar to how Spring Boot's BOM manages Spring dependencies.

#### Maven Configuration

Add the BOM to your `pom.xml` in the `<dependencyManagement>` section:

```xml
<dependencyManagement>
    <dependencies>
        <!-- Embabel Agent Experimental BOM -->
        <dependency>
            <groupId>com.embabel.agent</groupId>
            <artifactId>embabel-agent-experimental-bom</artifactId>
            <version>0.3.4-SNAPSHOT</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>
```

Then add the specific experimental modules you need without specifying versions:

```xml
<dependencies>
    <!-- Example: Adding Agent Evaluation -->
    <dependency>
        <groupId>com.embabel.agent</groupId>
        <artifactId>embabel-agent-eval</artifactId>
    </dependency>
    
    <!-- Example: Adding Remote Agent Execution -->
    <dependency>
        <groupId>com.embabel.agent</groupId>
        <artifactId>embabel-agent-remote</artifactId>
    </dependency>
</dependencies>
```

#### Gradle Configuration (Kotlin DSL)

Add the BOM to your `build.gradle.kts`:

```kotlin
dependencies {
    // Import the Embabel Agent Experimental BOM
    implementation(platform("com.embabel.agent:embabel-agent-experimental-bom:0.3.4-SNAPSHOT"))
    
    // Add specific experimental modules (versions managed by BOM)
    implementation("com.embabel.agent:embabel-agent-eval")
    implementation("com.embabel.agent:embabel-agent-remote")
}
```

#### Gradle Configuration (Groovy DSL)

Add the BOM to your `build.gradle`:

```groovy
dependencies {
    // Import the Embabel Agent Experimental BOM
    implementation platform('com.embabel.agent:embabel-agent-experimental-bom:0.3.4-SNAPSHOT')
    
    // Add specific experimental modules (versions managed by BOM)
    implementation 'com.embabel.agent:embabel-agent-eval'
    implementation 'com.embabel.agent:embabel-agent-remote'
}
```

### Repository Configuration

To access experimental modules, configure the Embabel repository in your build configuration:

#### Maven

Add to your `pom.xml`:

```xml
<repositories>
    <repository>
        <id>embabel-releases</id>
        <url>https://repo.embabel.com/artifactory/libs-release</url>
        <releases>
            <enabled>true</enabled>
        </releases>
        <snapshots>
            <enabled>true</enabled>
        </snapshots>
    </repository>
    <repository>
        <id>embabel-snapshots</id>
        <url>https://repo.embabel.com/artifactory/libs-snapshot</url>
        <snapshots>
            <enabled>true</enabled>
        </snapshots>
    </repository>
</repositories>
```

#### Gradle (Kotlin DSL)

Add to your `build.gradle.kts`:

```kotlin
repositories {
    maven {
        url = uri("https://repo.embabel.com/artifactory/libs-release")
    }
    maven {
        url = uri("https://repo.embabel.com/artifactory/libs-snapshot")
    }
}
```
## Important Considerations

### API Stability

⚠️ **Experimental modules may undergo breaking changes** between minor versions. While we strive for stability, the experimental nature allows for rapid iteration and improvement based on feedback.

### Production Use

Experimental modules are production-quality in terms of code quality and testing, but consider:

- **API Evolution**: Be prepared for API changes in future releases
- **Migration Path**: Monitor the main repository for promotion announcements
- **Community Support**: Join our [Discord](https://discord.gg/t6bjkyj93q) for discussions and support

### Version Alignment

Always use the BOM to ensure version consistency across experimental modules. The BOM is versioned independently from the main framework to allow for faster iteration cycles.

## Contributing

We welcome contributions and feedback! If you're using experimental modules:

1. **Share Your Experience**: Let us know how you're using these modules in production
2. **Report Issues**: Help us identify bugs and edge cases
3. **Suggest Improvements**: API feedback is invaluable during the experimental phase
4. **Contribute Code**: Submit PRs for enhancements or fixes

## Support

- **Documentation**: [https://docs.embabel.com](https://docs.embabel.com)
- **Discord Community**: [Join our Discord](https://discord.gg/t6bjkyj93q)
- **Issues**: [GitHub Issues](https://github.com/embabel/embabel-agent-experimental/issues)

## License

This project is licensed under the Apache License 2.0 - see the [LICENSE](LICENSE) file for details.
