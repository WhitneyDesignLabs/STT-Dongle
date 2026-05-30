Gradle wrapper note
===================

This folder is missing `gradle-wrapper.jar` on purpose.

`gradle-wrapper.jar` is a binary that cannot be hand-authored as text. It is
regenerated automatically by Android Studio the first time you open the project,
or by running:

    gradle wrapper --gradle-version 8.7

from a machine that already has a Gradle 8.7 (or newer) install. After that the
`./gradlew` / `gradlew.bat` scripts and this jar will be present and the project
builds normally.

The pinned distribution (Gradle 8.7) is declared in
`gradle-wrapper.properties` in this same folder, which IS hand-authored and
correct. Android Gradle Plugin 8.5.2 requires Gradle 8.7+.
