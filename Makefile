### Standard target architectures:
ARCHITECTURES = amd64 arm32v7
### This image will be used for the onos runtime:
IMAGE_TARGET = openjdk:8-jre-slim
### Quemu specific args:
MULTIARCH = multiarch/qemu-user-static:register
QEMU_VERSION = v2.11.0
### Version / Tag of the image
VERSION = $(shell cat VERSION)

### Check if REPO and TAG are set
ifeq ($(REPO),)
  REPO = chrisioa/myonos
endif
ifeq ($(CUSTOM_TAG),)
	TAG = $(VERSION)
else
	TAG = $(CUSTOM_TAG)
endif

	
$(ARCHITECTURES):
	@docker run --rm --privileged $(MULTIARCH) --reset
	@docker build \
			--build-arg IMAGE_TARGET=$@/$(IMAGE_TARGET) \
			--build-arg QEMU=$(strip $(call qemuarch,$@)) \
			--build-arg QEMU_VERSION=$(QEMU_VERSION) \
			--build-arg ARCH=$@ \
			--build-arg BUILD_DATE=$(shell date -u +"%Y-%m-%dT%H:%M:%SZ") \
			--build-arg VCS_REF=$(shell git rev-parse --short HEAD) \
			--build-arg VCS_URL=$(shell git config --get remote.origin.url) \
			-t $(REPO):linux-$@-$(TAG) .


onos:
	@docker build -f onosDockerfile -t chrisioa/myonosbase .



	
push:
	@$(foreach arch,$(ARCHITECTURES), docker push $(REPO):linux-$(arch)-$(TAG);)
			
test:
	@docker run -e ONOS_APPS=openflow,pathpainter,project.ioannidis.onosApp -d --rm --name onos -p 6653 -p 6640 -p 8181 -p 8101 -p 9876  chrisioa/myonos:linux-amd64-$(VERSION)
	@sleep 20
	@for i in 1 2 3 4 5 6 7 8 9 10; do if docker exec onos /root/onos/bin/onos-app localhost list | grep -oE project.ioannidis.onosApp/."{1,20}"/xml/features\",\"state\":\"ACTIVE\"; then echo "Success" && break; elif [ $$i -eq 5 ]; then echo "Test Failed" && exit 42; else echo "Not found, trying again..." && sleep 10; fi || sleep 10; done
	@docker container stop onos
	
		
manifest:
	@wget -O dockermanifest https://6582-88013053-gh.circle-artifacts.com/1/work/build/docker-linux-amd64
	@chmod +x dockermanifest
	@./dockermanifest manifest create $(REPO):$(TAG) \
			$(foreach arch,$(ARCHITECTURES), $(REPO):linux-$(arch)-$(TAG)) --amend
	@$(foreach arch,$(ARCHITECTURES), ./dockermanifest manifest annotate \
			$(REPO):$(TAG) $(REPO):linux-$(arch)-$(TAG) \
			--os linux $(strip $(call convert_variants,$(arch)));)
	@./dockermanifest manifest push $(REPO):$(TAG)
	@rm -f dockermmanifest
			
			
# Needed convertions for different architecture naming schemes
# Convert qemu archs to naming scheme of https://github.com/multiarch/qemu-user-static/releases
define qemuarch
	$(shell echo $(1) | sed -e "s|arm32.*|arm|g" -e "s|arm64.*|aarch64|g" -e "s|amd64|x86_64|g")
endef
# Convert GOARCH to naming scheme of https://gist.github.com/asukakenji/f15ba7e588ac42795f421b48b8aede63
define prometheusarch
	$(shell echo $(1) | sed -e "s|arm32v5|armv5|g" -e "s|arm32v6|armv6|g" -e "s|arm32v7|armv7|g" -e "s|arm64.*|arm64|g" -e "s|i386|386|g")
endef
# Convert Docker manifest entries according to https://docs.docker.com/registry/spec/manifest-v2-2/#manifest-list-field-descriptions
define convert_variants
	$(shell echo $(1) | sed -e "s|amd64|--arch amd64|g" -e "s|i386|--arch 386|g" -e "s|arm32v5|--arch arm --variant v5|g" -e "s|arm32v6|--arch arm --variant v6|g" -e "s|arm32v7|--arch arm --variant v7|g" -e "s|arm64v8|--arch arm64 --variant v8|g" -e "s|ppc64le|--arch ppc64le|g" -e "s|s390x|--arch s390x|g")
endef

