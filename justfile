# List available recipes.
default:
    @just --list

# Run the desktop app in development mode.
dev:
    ./gradlew run

# Run the JVM test task.
test:
    ./gradlew test

# Build the AppImage app bundle (dpkg-free; packageDeb needs dpkg-deb, which
# Fedora does not ship).
build:
    ./gradlew packageAppImage

# Build the AppImage and stage a timestamped copy in _apk/.
build-appimage:
    ./gradlew stageAppImage

# Run the packaged app. LD_LIBRARY_PATH is scrubbed because terminals spawned
# from AppImage-hosted editors (e.g. T3 Code) inject their mount's lib dir,
# which makes the Compose native launcher segfault in setenv at JVM startup.
run-package:
    env -u LD_LIBRARY_PATH ./build/compose/binaries/main/app/lyrics-float/bin/lyrics-float
