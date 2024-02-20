# Required to build deb package in e2e tests
# To build:
# $ docker build -t registry.jetbrains.team/p/amper/containers/android-sdk:latest .
# To push to docker registry:
# $ docker login registry.jetbrains.team -u <FirstName.LastName>
# $ docker push registry.jetbrains.team/p/amper/containers/android-sdk:latest

FROM thyrlian/android-sdk

RUN apt update
RUN apt install -y binutils fakeroot
RUN /opt/android-sdk/cmdline-tools/tools/bin/sdkmanager  "platform-tools" "platforms;android-34"
RUN /opt/android-sdk/cmdline-tools/tools/bin/sdkmanager  "platform-tools" "platforms;android-33"
RUN /opt/android-sdk/cmdline-tools/tools/bin/sdkmanager  "platform-tools" "platforms;android-31"
RUN /opt/android-sdk/cmdline-tools/tools/bin/sdkmanager "build-tools;33.0.0"
RUN /opt/android-sdk/cmdline-tools/tools/bin/sdkmanager "build-tools;33.0.1"