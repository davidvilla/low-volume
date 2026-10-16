JDK_DIR      ?= $(HOME)/.local/share/jdk-17
SDK_DIR      ?= $(HOME)/Android/Sdk
GRADLE_VERSION := 8.10.2
GRADLE_DIR   ?= $(HOME)/.local/share/gradle-$(GRADLE_VERSION)
JDK_URL      := https://api.adoptium.net/v3/binary/latest/17/ga/linux/x64/jdk/hotspot/normal/eclipse
SDK_TOOLS_URL := https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
GRADLE_URL   := https://services.gradle.org/distributions/gradle-$(GRADLE_VERSION)-bin.zip
SDK_PACKAGES := "platforms;android-35" "build-tools;35.0.0" "platform-tools"

SDKMANAGER := $(SDK_DIR)/cmdline-tools/latest/bin/sdkmanager
ADB        := $(SDK_DIR)/platform-tools/adb
APK        := app/build/outputs/apk/release/app-release.apk
GRADLE     := JAVA_HOME=$(JDK_DIR) $(GRADLE_DIR)/bin/gradle
TEST_APK   := app/build/outputs/apk/androidTest/release/app-release-androidTest.apk
TEST_PKG   := es.vibecode.lowvolume.test
TEST_LOG   := app/build/device-test.log

.PHONY: all install-depends build lint test test-device install clean clean-all

all: build

install-depends: $(JDK_DIR)/bin/java $(GRADLE_DIR)/bin/gradle $(SDK_DIR)/platforms/android-35 local.properties

$(JDK_DIR)/bin/java:
	mkdir -p $(JDK_DIR)
	curl -fL "$(JDK_URL)" | tar xz -C $(JDK_DIR) --strip-components=1

$(GRADLE_DIR)/bin/gradle:
	curl -fL -o /tmp/gradle-$(GRADLE_VERSION).zip "$(GRADLE_URL)"
	rm -rf $(GRADLE_DIR) /tmp/gradle-$(GRADLE_VERSION)
	unzip -q /tmp/gradle-$(GRADLE_VERSION).zip -d /tmp
	mkdir -p $(dir $(GRADLE_DIR))
	mv /tmp/gradle-$(GRADLE_VERSION) $(GRADLE_DIR)
	rm /tmp/gradle-$(GRADLE_VERSION).zip

$(SDKMANAGER):
	mkdir -p $(SDK_DIR)/cmdline-tools
	curl -fL -o /tmp/android-cmdline-tools.zip "$(SDK_TOOLS_URL)"
	rm -rf $(SDK_DIR)/cmdline-tools/latest $(SDK_DIR)/cmdline-tools/cmdline-tools
	unzip -q /tmp/android-cmdline-tools.zip -d $(SDK_DIR)/cmdline-tools
	mv $(SDK_DIR)/cmdline-tools/cmdline-tools $(SDK_DIR)/cmdline-tools/latest
	rm /tmp/android-cmdline-tools.zip

$(SDK_DIR)/platforms/android-35: $(SDKMANAGER) $(JDK_DIR)/bin/java
	yes | JAVA_HOME=$(JDK_DIR) $(SDKMANAGER) --sdk_root=$(SDK_DIR) $(SDK_PACKAGES) > /dev/null

local.properties:
	echo "sdk.dir=$(SDK_DIR)" > $@

build: install-depends
	$(GRADLE) assembleRelease
	@echo "APK: $(APK)"

lint: install-depends
	$(GRADLE) lintRelease

test: install-depends
	$(GRADLE) testReleaseUnitTest

# Runs the instrumented tests inside the app installed on the connected phone.
# The app process is restarted afterwards so the service comes back if enabled.
test-device: install
	$(GRADLE) assembleReleaseAndroidTest
	$(ADB) install -r $(TEST_APK)
	$(ADB) shell am instrument -w $(TEST_PKG)/androidx.test.runner.AndroidJUnitRunner | tee $(TEST_LOG)
	$(ADB) uninstall $(TEST_PKG) > /dev/null
	$(ADB) shell am start -n es.vibecode.lowvolume/.MainActivity > /dev/null
	grep -q '^OK (' $(TEST_LOG)

install: build
	$(ADB) install -r $(APK)

clean:
	-test -x $(GRADLE_DIR)/bin/gradle && $(GRADLE) clean
	rm -rf .gradle .kotlin

# Also removes the downloaded tools and the Gradle cache shared with other projects.
clean-all: clean
	-test -x $(GRADLE_DIR)/bin/gradle && $(GRADLE) --stop
	rm -rf $(HOME)/.gradle/caches
	rm -rf $(SDK_DIR) $(JDK_DIR) $(GRADLE_DIR)
	rm -f local.properties
