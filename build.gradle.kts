plugins {
    id("com.falsepattern.fpgradle-mc") version ("0.15.1")
}

group = "mega"

minecraft_fp {
    java {
        compatibility = modern
    }
    mod {
        modid = "betterloadingscreen"
        name = "MEGA Loading Screen"
        rootPkg = "alexiil.mods.load"
    }
    core {
        coreModClass = "coremod.LoadingScreenLoadPlugin"
    }
    tokens {
        tokenClass = "Tags"
    }
}

repositories {
    exclusive(mavenpattern(), "com.falsepattern")
}

dependencies {
    apiSplit("com.falsepattern:falsepatternlib-mc1.7.10:1.6.0")
}