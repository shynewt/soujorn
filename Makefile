.PHONY: debug release release-key test unit verify screenshots geodata icon gradle clean docker-image

DOCKER_IMAGE := sojourn-build
GRADLE_BUILD_HOME := $(CURDIR)/.gradle-build-cache
ANDROID_SDK_HOME := $(CURDIR)/.android-cache
# Every build is numbered by its date and hour, so each one installs as an update of the last.
VERSION_CODE := $(shell date +%Y%m%d%H)
VERSION_NAME := $(shell date +%Y.%m.%d)
RELEASE_APK := sojourn-$(VERSION_NAME).apk
UNSIGNED_APK := app/build/outputs/apk/release/app-release-unsigned.apk
# An optional local file holding the release keystore password, so builds needn't ask.
PASSWORD_FILE := signing/release.password
TTY := $(shell [ -t 0 ] && echo -t)
HOST_UID := $(shell id -u)
HOST_GID := $(shell id -g)
# Rootless podman already maps the container's root to the invoking user, and a chown to
# that user's uid would map it onto a subordinate uid the host user cannot write.
HAND_BACK := $(if $(findstring podman,$(shell docker --version 2>/dev/null)),true,chown -Rf $(HOST_UID):$(HOST_GID))
# Mounts are relabelled (:z) so an SELinux host (Fedora…) lets the container use them; the
# option is ignored where SELinux is off.

# Everything builds inside the container, so the host needs no JDK or Android SDK.
# Gradle runs as root to write the mounted caches, then hands back what it touched.
define gradle
	mkdir -p $(GRADLE_BUILD_HOME) $(ANDROID_SDK_HOME)
	docker run --rm \
		-u 0:0 \
		-v $(CURDIR):/project:z \
		-v $(GRADLE_BUILD_HOME):/home/gradle/.gradle:z \
		-v $(ANDROID_SDK_HOME):/home/gradle/.android:z \
		-e GRADLE_USER_HOME=/home/gradle/.gradle \
		-w /project $(DOCKER_IMAGE) \
		sh -c "./gradlew --no-daemon -PversionCode=$(VERSION_CODE) -PversionName=$(VERSION_NAME) $(1); status=\$$?; $(HAND_BACK) /project/app /project/build /project/.gradle-build-cache /project/.android-cache 2>/dev/null; exit \$$status"
endef

docker-image:
	docker build -t $(DOCKER_IMAGE) .

debug: docker-image
	$(call gradle,assembleDebug)
	@ls -lh app/build/outputs/apk/debug/*.apk

# Builds the shrunk release APK and signs it with signing/release.jks. The password comes from
# SOJOURN_KEYSTORE_PASSWORD, then signing/release.password, or is asked for.
release: docker-image
	@test -f signing/release.jks || { echo "signing/release.jks is missing; run make release-key first (see README)"; exit 1; }
	$(call gradle,assembleRelease)
	@if [ -z "$$SOJOURN_KEYSTORE_PASSWORD" ] && [ -f $(PASSWORD_FILE) ]; then \
		SOJOURN_KEYSTORE_PASSWORD=$$(cat $(PASSWORD_FILE)); \
	fi; \
	if [ -z "$$SOJOURN_KEYSTORE_PASSWORD" ]; then \
		trap 'stty echo' EXIT INT; stty -echo; printf 'Release keystore password: '; read -r SOJOURN_KEYSTORE_PASSWORD; stty echo; echo; \
	fi; \
	export SOJOURN_KEYSTORE_PASSWORD; \
	docker run --rm -u 0:0 -e SOJOURN_KEYSTORE_PASSWORD -v $(CURDIR):/project:z -w /project $(DOCKER_IMAGE) \
		sh -c "scripts/sign-release.sh $(UNSIGNED_APK) $(RELEASE_APK) && $(HAND_BACK) $(RELEASE_APK)"
	@echo "Signed release: $(RELEASE_APK) (version $(VERSION_NAME), code $(VERSION_CODE))"
	@ls -lh $(RELEASE_APK)

# Creates the release key. Run once, then back up signing/release.jks and its password.
release-key: docker-image
	docker run --rm -i $(TTY) -u 0:0 -e SOJOURN_KEYSTORE_PASSWORD -v $(CURDIR):/project:z -w /project $(DOCKER_IMAGE) \
		sh -c "scripts/create-release-key.sh && $(HAND_BACK) signing"

test: docker-image
	$(call gradle,testDebugUnitTest)

# Runs the unit tests matching TESTS, e.g. make unit TESTS='*RuleEngine*'.
unit: docker-image
	$(call gradle,testDebugUnitTest --tests '$(TESTS)')

verify: docker-image
	$(call gradle,testDebugUnitTest assembleDebug lintDebug)

# Renders the screenshot tests to app/build/screenshots for visual review.
screenshots: docker-image
	$(call gradle,testDebugUnitTest -Pscreenshots)

# Runs any Gradle tasks, e.g. make gradle ARGS='lintDebug --stacktrace'.
gradle: docker-image
	$(call gradle,$(ARGS))

# Regenerates the border assets from a Natural Earth admin-0 GeoJSON download, e.g.
# make geodata NE=/path/to/ne_50m_admin_0_countries.geojson
geodata:
	python3 -I tools/build_geodata.py $(NE) app/src/main/assets

# Regenerates the launcher, themed and notification icons from tools/build_icon.py.
icon:
	python3 -I tools/build_icon.py app/src/main/res

clean:
	rm -rf app/build build .gradle .kotlin sojourn-*.apk
