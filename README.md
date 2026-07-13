# Pitchout

Pitchout is a Cookie Build minigame plugin for Paper. It depends on the
[CookieDough](https://github.com/Guillaume351/CookieDough) core plugin.

## Build

Requirements:

- Java 25
- A CookieDough checkout next to this repository (`../CookieDough`)

The Gradle wrapper is pinned to Gradle 9.6.1 and provisions a Java 25 toolchain
when one is not already installed:

```shell
./gradlew clean build
```

The plugin JAR is written to `build/libs/`.

CI checks out CookieDough's `dev` branch by default. Set the repository variable
`COOKIE_DOUGH_REF` when a Pitchout branch must be tested against a matching
CookieDough feature branch.

## Map templates

Map archives are read from the server's `pitchout_maps/` directory. Each game is
loaded as a Paper namespaced world under
`<level-name>/dimensions/pitchout/<game-uuid>` and removed after the match.
Template archives must not contain `session.lock` or `uid.dat`.
