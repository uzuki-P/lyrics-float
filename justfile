# List available recipes.
default:
    @just --list

# Run the desktop app in development mode.
dev:
    ./gradlew run

# Run the JVM test task.
test:
    ./gradlew test

# Build a native package for the current operating system.
build:
    ./gradlew packageDistributionForCurrentOS

# Build the Linux AppImage package.
build-appimage:
    ./gradlew packageAppImageFile
