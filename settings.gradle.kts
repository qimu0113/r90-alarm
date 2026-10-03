pluginManagement {
    repositories {
        // 国内镜像优先。
        //
        // 为什么必须加：本机实测 repo1.maven.org 直连 15 秒超时（HTTP 000），
        // 只有 mavenCentral() 的话依赖解析会卡住直到超时。
        // 阿里云这几个镜像实测 0.3 秒响应，且产物与官方同源。
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/central") }

        // 官方源留作兜底：镜像偶发缺新版本时还能回源
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/central") }
        google()
        mavenCentral()
    }
}

rootProject.name = "R90Alarm"
include(":app")
