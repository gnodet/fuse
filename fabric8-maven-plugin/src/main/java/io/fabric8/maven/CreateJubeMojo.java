/**
 *  Copyright 2005-2014 Red Hat, Inc.
 *
 *  Red Hat licenses this file to you under the Apache License, version
 *  2.0 (the "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 *  implied.  See the License for the specific language governing
 *  permissions and limitations under the License.
 */
package io.fabric8.maven;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.fabric8.agent.download.DownloadCallback;
import io.fabric8.agent.download.DownloadManager;
import io.fabric8.agent.download.DownloadManagers;
import io.fabric8.agent.download.Downloader;
import io.fabric8.agent.download.StreamProvider;
import io.fabric8.agent.internal.MapUtils;
import io.fabric8.agent.model.BundleInfo;
import io.fabric8.agent.model.ConfigFile;
import io.fabric8.agent.model.Feature;
import io.fabric8.agent.model.Repository;
import io.fabric8.agent.region.SubsystemResolver;
import io.fabric8.agent.repository.StaticRepository;
import io.fabric8.agent.resolver.ResourceBuilder;
import io.fabric8.agent.service.MetadataBuilder;
import io.fabric8.agent.service.RequirementSort;
import io.fabric8.api.FabricService;
import io.fabric8.api.GitContext;
import io.fabric8.api.LockHandle;
import io.fabric8.api.PlaceholderResolver;
import io.fabric8.api.Profile;
import io.fabric8.api.ProfileBuilder;
import io.fabric8.api.ProfileRegistry;
import io.fabric8.api.ProfileService;
import io.fabric8.api.Profiles;
import io.fabric8.api.RuntimeProperties;
import io.fabric8.api.Version;
import io.fabric8.api.VersionBuilder;
import io.fabric8.api.gravia.IllegalStateAssertion;
import io.fabric8.api.scr.AbstractRuntimeProperties;
import io.fabric8.common.util.Files;
import io.fabric8.common.util.MultiException;
import io.fabric8.git.internal.DefaultPullPushPolicy;
import io.fabric8.git.internal.FabricGitServiceImpl;
import io.fabric8.git.internal.GitDataStoreImpl;
import io.fabric8.git.internal.GitHelpers;
import io.fabric8.git.internal.GitProxyRegistrationHandler;
import io.fabric8.internal.ProfileServiceImpl;
import io.fabric8.service.ComponentConfigurer;
import io.fabric8.service.PermitManagerImpl;
import io.fabric8.utils.DataStoreUtils;
import io.fabric8.zookeeper.utils.InterpolationHelper;
import org.apache.felix.utils.properties.Properties;
import org.apache.felix.utils.version.VersionRange;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.DefaultArtifact;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.descriptor.PluginDescriptor;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Execute;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.MavenProjectHelper;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.errors.UnsupportedCredentialItem;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.transport.CredentialItem;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.FetchResult;
import org.eclipse.jgit.transport.URIish;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.mvel2.templates.CompiledTemplate;
import org.mvel2.templates.TemplateCompiler;
import org.mvel2.templates.TemplateRuntime;
import org.osgi.framework.Constants;
import org.osgi.framework.wiring.BundleRevision;
import org.osgi.resource.Resource;

import static io.fabric8.agent.DeploymentAgent.getMetadata;
import static io.fabric8.agent.internal.MapUtils.addToMapSet;
import static io.fabric8.agent.resolver.ResourceUtils.getUri;
import static io.fabric8.agent.service.Constants.DEFAULT_FEATURE_RESOLUTION_RANGE;
import static io.fabric8.agent.service.Constants.ROOT_REGION;
import static io.fabric8.agent.utils.AgentUtils.downloadRepositories;

@Mojo(name = "jube", defaultPhase = LifecyclePhase.PACKAGE, requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME)
@Execute(phase = LifecyclePhase.PACKAGE)
public class CreateJubeMojo extends AbstractMojo {

    @Parameter(property = "distribution", defaultValue = "org.apache.karaf:apache-karaf")
    private String distribution;

    @Parameter(property = "profilesGitRemoteUrl")
    private String profilesGitRemoteUrl;

    @Parameter(property = "profilesGitLocalUrl")
    private String profilesGitLocalUrl;

    @Parameter(property = "profilesDirectory")
    private String profilesDirectory;

    @Parameter(property = "buildDir", defaultValue = "${project.build.directory}/karaf")
    private String buildDir;

    @Parameter(property = "gitDir", defaultValue = "${project.build.directory}/git")
    private String gitDir;

    @Parameter(property = "javase")
    private String javase;

    @Parameter(property = "versionId")
    private String versionId;

    @Parameter(property = "profileIds")
    private List<String> profileIds;

    @Parameter(property = "ports")
    private Map<String, String> ports;

    /**
     * Name of the generated image zip file
     */
    @Parameter(property = "outFile", defaultValue = "${project.build.directory}/${project.artifactId}-${project.version}-image.zip")
    private File outputZipFile;

    /**
     * The artifact type for attaching the generated image zip file to the project
     */
    @Parameter(property = "artifactType", defaultValue = "zip")
    private String artifactType = "zip";

    /**
     * The artifact classifier for attaching the generated image zip file to the project
     */
    @Parameter(property = "artifactClassifier", defaultValue = "image")
    private String artifactClassifier = "image";

    @Component
    private MavenProjectHelper projectHelper;

    @Component
    protected PluginDescriptor pluginDescriptor;

    @Component
    protected MavenProject project;

    private String karafHome;
    private RuntimeProperties runtimeProperties;
    private ProfileRegistry profileRegistry;
    private ProfileService profileService;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        try {
            Artifact karafDistro = project.getArtifactMap().get(distribution);
            if (karafDistro == null) {
                karafDistro = pluginDescriptor.getArtifactMap().get(distribution);
            }
            if (karafDistro == null) {
                throw new IllegalStateException("Could not find a dependency matching: " + distribution);
            }
            Unzip.extractZipFile(karafDistro.getFile().toPath(), Paths.get(buildDir), true);
            File[] children = new File(buildDir).listFiles();
            if (children == null) {
                throw new IllegalStateException("The extracted distribution does not contain any files");
            }
            if (children.length == 1) {
                karafHome = children[0].getPath();
            } else {
                karafHome = buildDir;
            }

            initServices();

            Profile profile = getEffectiveProfile();

            writeConfigurations(profile);

            resolveProfile(profile);

            Unzip.createZipFile(Paths.get(karafHome), outputZipFile.toPath());
            getLog().info("Created image zip: " + outputZipFile);

            attachArtifactToBuild();

        } catch (Exception e) {
            throw new MojoExecutionException("Unable to generate distribution", e);
        }
    }

    protected void attachArtifactToBuild() {
        projectHelper.attachArtifact(project, artifactType, artifactClassifier, outputZipFile);
    }

    private void writeConfigurations(Profile profile) throws Exception {
        Map<String, Map<String, String>> configs = new HashMap<>(profile.getConfigurations());
        Map<String, byte[]> configFiles = profile.getFileConfigurations();

        for (Map.Entry<String, Map<String, String>> cfg : configs.entrySet()) {
            File file = runtimeProperties.getConfPath().resolve(cfg.getKey() + ".cfg").toFile();
            Properties original = new Properties(file);
            for (Map.Entry<String, String> v : cfg.getValue().entrySet()) {
                original.put(v.getKey(), v.getValue());
            }
            original.save();
        }
        for (Map.Entry<String, byte[]> cfg : configFiles.entrySet()) {
            if (!cfg.getKey().endsWith(Profile.PROPERTIES_SUFFIX)) {
                Files.writeToFile(runtimeProperties.getConfPath().resolve(cfg.getKey()).toFile(), cfg.getValue());
            }
        }
    }

    private void resolveProfile(Profile profile) throws Exception {
        Hashtable<String, String> agentConfig = new Hashtable<>(profile.getConfiguration("io.fabric8.agent"));

        for (Map.Entry<String, String> entry : agentConfig.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            InterpolationHelper.SubstitutionCallback callback = new InterpolationHelper.SubstitutionCallback() {
                @Override
                public String getValue(String key) {
                    String value = runtimeProperties.getProperty(key);
                    if (value == null) {
                        throw new IllegalStateException("No substitution for " + key);
                    }
                    return value;
                }
            };
            String substValue = InterpolationHelper.substVars(value, key, null, agentConfig, callback, false);
            agentConfig.put(key, substValue);
        }

        final Properties configProps = new Properties(runtimeProperties.getConfPath().resolve("config.properties").toFile());
        final Properties systemProps = new Properties(runtimeProperties.getConfPath().resolve("system.properties").toFile());
        for (String key : agentConfig.keySet()) {
            if (key.equals("framework")) {
                // TODO
            } else if (key.startsWith("config.")) {
                String k = key.substring("config.".length());
                String v = agentConfig.get(key);
                configProps.put(k, v);
            } else if (key.startsWith("system.")) {
                String k = key.substring("system.".length());
                String v = agentConfig.get(key);
                systemProps.put(k, v);
            } else if (key.startsWith("lib.")) {
                // TODO
            } else if (key.startsWith("endorsed.")) {
                // TODO
            } else if (key.startsWith("extension.")) {
                // TODO
            } else if (key.startsWith("etc.")) {
                // TODO
            }
        }
        configProps.save();
        systemProps.save();

        ScheduledExecutorService executor = Executors.newScheduledThreadPool(8);

        DownloadManager manager;
        MavenResolver mvnResolver;
        final Map<String, Repository> repositories;
        Map<String, Feature[]> repos = new HashMap<>();
        Map<String, Feature> allFeatures = new HashMap<>();
        try {
            mvnResolver = MavenResolvers.createMavenResolver(agentConfig, "org.ops4j.pax.url.mvn");
            manager = DownloadManagers.createDownloadManager(mvnResolver, executor);
            repositories = downloadRepositories(manager, getPrefixedProperties(agentConfig, "repository.")).call();
            for (String repoUri : repositories.keySet()) {
                Feature[] features = repositories.get(repoUri).getFeatures();
                // Ack features to inline configuration files urls
                for (Feature feature : features) {
                    for (BundleInfo bi : feature.getBundles()) {
                        String loc = bi.getLocation();
                        String nloc = null;
                        if (loc.contains("file:")) {
                            for (ConfigFile cfi : feature.getConfigurationFiles()) {
                                if (cfi.getFinalname().substring(1)
                                        .equals(loc.substring(loc.indexOf("file:") + "file:".length()))) {
                                    nloc = cfi.getLocation();
                                }
                            }
                        }
                        if (nloc != null) {
                            bi.setLocation(loc.substring(0, loc.indexOf("file:")) + nloc);
                        }
                    }
                    allFeatures.put(feature.getId(), feature);
                }
                repos.put(repoUri, features);
            }
        } catch (Exception e) {
            throw new IOException("Unable to load features descriptors", e);
        }

        Map<String, Map<VersionRange, Map<String, String>>> metadata = getMetadata(agentConfig, "metadata#");

        FakeSystemBundle systemBundle = getSystemBundleResource(metadata);

        final BundleRevision systemBundleRevision = systemBundle.adapt(BundleRevision.class);
        Map<String, Set<BundleRevision>> system = new HashMap<String, Set<BundleRevision>>();
        addToMapSet(system, ROOT_REGION, systemBundleRevision);

        Map<String, Set<String>> requirements = new HashMap<>();
        for (String feature : getPrefixedProperties(agentConfig, "feature.")) {
            addToMapSet(requirements, ROOT_REGION, "feature:" + feature);
        }
        for (String bundle : getPrefixedProperties(agentConfig, "bundle.")) {
            addToMapSet(requirements, ROOT_REGION, "bundle:" + bundle);
        }
        for (String req : getPrefixedProperties(agentConfig, "req.")) {
            addToMapSet(requirements, ROOT_REGION, "req:" + req);
        }

        Callable<Map<String, org.osgi.resource.Resource>> optional = loadResources(manager, metadata, getPrefixedProperties(agentConfig, "optional."));

        SubsystemResolver resolver = new SubsystemResolver(manager);
        resolver.prepare(
                allFeatures.values(),
                requirements,
                system
        );
        resolver.resolve(
                new MetadataBuilder(metadata),
                getPrefixedProperties(agentConfig, "override."),
                DEFAULT_FEATURE_RESOLUTION_RANGE,
                new StaticRepository(optional.call().values()));


        int initialBundleStartLevel = 80;
        Map<String, Map<String, BundleInfo>> bundleInfos = resolver.getBundleInfos();

        Map<Resource, Integer> startLevels = new HashMap<>();
        for (Map.Entry<String, Set<Resource>> entry : resolver.getBundlesPerRegions().entrySet()) {
            String region = entry.getKey();
            for (Resource resource : entry.getValue()) {
                BundleInfo bi = bundleInfos.get(region).get(getUri(resource));
                if (bi != null) {
                    int sl = bi.getStartLevel() > 0 ? bi.getStartLevel() : initialBundleStartLevel;
                    startLevels.put(resource, sl);
                }
            }
        }

        StringBuilder sb = new StringBuilder();
        Map<Integer, Set<Resource>> bundles = MapUtils.invert(startLevels);
        for (int level : new TreeSet<>(bundles.keySet())) {
            Collection<Resource> resources = RequirementSort.sort(bundles.get(level));
            for (Resource res : resources) {
                String uri = getUri(res);
                String path = getMvnPath(uri);
                File file = runtimeProperties.getHomePath().resolve("system").resolve(path).toFile();
                Files.copy(resolver.getProviders().get(uri).getFile(), file);
                sb.append(path).append("=").append(level).append("\n");
            }
        }
        Files.writeToFile(runtimeProperties.getConfPath().resolve("startup.properties").toFile(),
                          sb.toString(), Charset.forName("UTF-8"));

    }

    public static String getMvnPath(String url)
    {
        String protocol = url.substring(0, url.indexOf(":"));
        if (protocol.equals("mvn"))
        {
            String[] parts = url.substring(4).split("/");
            if ((parts.length >= 3) && (parts.length <= 5)) {
                String groupId = parts[0];
                String artifactId = parts[1];
                String version = parts[2];
                String type = parts.length >= 4 ? "." + parts[3] : ".jar";
                String qualifier = parts.length >= 5 ? "-" + parts[4] : "";
                return groupId.replace('.', '/') + "/" + artifactId + "/" + version + "/" + artifactId + "-" + version + qualifier + type;
            }
        }
        throw new IllegalStateException("Can not convert mvn url: " + url);
    }

    public static Callable<Map<String, org.osgi.resource.Resource>> loadResources(
            DownloadManager manager,
            Map<String, Map<VersionRange, Map<String, String>>> metadata,
            Set<String> uris)
            throws MultiException, InterruptedException, MalformedURLException {
        final Map<String, org.osgi.resource.Resource> resources = new HashMap<>();
        final Downloader downloader = manager.createDownloader();
        final MetadataBuilder builder = new MetadataBuilder(metadata);
        final DownloadCallback callback = new DownloadCallback() {
            @Override
            public void downloaded(StreamProvider provider) throws Exception {
                String uri = provider.getUrl();
                Map<String, String> headers = builder.getMetadata(uri, provider.getFile());
                org.osgi.resource.Resource resource = ResourceBuilder.build(uri, headers);
                synchronized (resources) {
                    resources.put(uri, resource);
                }
            }
        };
        for (String uri : uris) {
            downloader.download(uri, callback);
        }
        return new Callable<Map<String, org.osgi.resource.Resource>>() {
            @Override
            public Map<String, org.osgi.resource.Resource> call() throws Exception {
                downloader.await();
                return resources;
            }
        };
    }

    private FakeSystemBundle getSystemBundleResource(Map<String, Map<VersionRange, Map<String, String>>> metadata) throws Exception {
        File configFile = runtimeProperties.getConfPath().resolve("config.properties").toFile();
        Properties configProps = PropertiesLoader.loadPropertiesOrFail(configFile);
//        copySystemProperties(configProps);
        if (javase == null) {
            configProps.put("java.specification.version", System.getProperty("java.specification.version"));
        } else {
            configProps.put("java.specification.version", javase);
        }
        configProps.substitute();

        Hashtable<String, String> headers = new Hashtable<>();
        headers.put(Constants.BUNDLE_MANIFESTVERSION, "2");
        headers.put(Constants.BUNDLE_SYMBOLICNAME, "system-bundle");
        headers.put(Constants.BUNDLE_VERSION, "0.0.0");

        String exportPackages = configProps.getProperty("org.osgi.framework.system.packages");
        if (configProps.containsKey("org.osgi.framework.system.packages.extra")) {
            exportPackages += "," + configProps.getProperty("org.osgi.framework.system.packages.extra");
        }
        headers.put(Constants.EXPORT_PACKAGE, exportPackages);

        String systemCaps = configProps.getProperty("org.osgi.framework.system.capabilities");
        headers.put(Constants.PROVIDE_CAPABILITY, systemCaps);

        new MetadataBuilder(metadata).overrideHeaders(headers);

        return new FakeSystemBundle(headers);
    }

    public static Set<String> getPrefixedProperties(Map<String, String> properties, String prefix) {
        Set<String> result = new HashSet<>();
        for (String key : properties.keySet()) {
            if (key.startsWith(prefix)) {
                String url = properties.get(key);
                if (url == null || url.length() == 0) {
                    url = key.substring(prefix.length());
                }
                if (url.length() > 0) {
                    result.add(url);
                }
            }
        }
        return result;
    }

    public Profile getEffectiveProfile() throws IOException {
        Profile profile = getOverlayProfile();

        final Map<String, Map<String, String>> configs = new HashMap<>(profile.getConfigurations());
        final Map<String, byte[]> configFiles = new HashMap<>(profile.getFileConfigurations());

        for (Map.Entry<String, Map<String, String>> cfg : configs.entrySet()) {
            final String pid = cfg.getKey();
            final Map<String, String> props = new HashMap<>(cfg.getValue());
            cfg.setValue(props);
            for (Map.Entry<String, String> e : props.entrySet()) {
                final String key = e.getKey();
                final String value = e.getValue();
                InterpolationHelper.SubstitutionCallback callback = new InterpolationHelper.SubstitutionCallback() {
                    public String getValue(String toSubstitute) {
                        return substitute(configs, pid, key, toSubstitute);
                    }
                };
                String substValue = InterpolationHelper.substVars(value, key, null, props, callback, false);
                if (substValue.startsWith("mvel:profile:")) {
                    String path = substValue.substring("mvel:profile:".length());
                    if (configFiles.containsKey(path)) {
                        CompiledTemplate compiledTemplate = TemplateCompiler.compileTemplate(new ByteArrayInputStream(configFiles.get(path)));
                        Map<String, Object> data = new HashMap<>();
                        data.put("profile", profile);
                        data.put("runtime", runtimeProperties);
                        String content = TemplateRuntime.execute(compiledTemplate, data).toString();
                        configFiles.put(path, content.getBytes());
                        substValue = "file:${karaf.etc}/" + path;
                    }
                }
                if (substValue.startsWith("profile:")) {
                    String path = substValue.substring("profile:".length());
                    if (configFiles.containsKey(path)) {
                        substValue = "file:${karaf.etc}/" + path;
                    }
                }
                if (substValue.matches(".*(?<!\\bmvn|\\bhttp|\\bhttps|\\bfile|\\bosgi):.*")) {
                    System.out.println("Warning: possible non substituted or url value for key " + key + " in pid " + pid + ": " + substValue);
                }
                props.put(key, substValue);
            }
        }

        return ProfileBuilder.Factory.createFrom(profile)
                .setFileConfigurations(configFiles)
                .setConfigurations(configs)
                .getProfile();
    }

    private String substitute(Map<String, Map<String, String>> configs, String pid, String key, String value) {
        if (value != null && value.contains(":")) {
            String scheme = value.substring(0, value.indexOf(":"));
            if ("checksum".equals(scheme)) {
                return "0";
            }
            if ("version".equals(scheme)) {
                Map<String, String> cfg = configs.get("io.fabric8.version");
                String targetProperty = value.substring(value.indexOf(":") + 1);
                if (cfg != null && cfg.containsKey(targetProperty)) {
                    return cfg.get(targetProperty);
                }
            }
            if ("profile".equals(scheme)) {
                Matcher matcher = Pattern.compile("profile:([^ /]+)/([^ =/]+)").matcher(value);
                if (matcher.matches()) {
                    String targetPid = matcher.group(1);
                    String targetProperty = matcher.group(2);
                    Map<String, String> cfg = configs.get(targetPid);
                    if (cfg != null && cfg.containsKey(targetProperty)) {
                        return cfg.get(targetProperty);
                    }
                }
            }
            if ("port".equals(scheme)) {
                if (ports != null && ports.containsKey(pid + "/" + key)) {
                    return ports.get(pid + "/" + key);
                }
            }
        } else if ("runtime.data".equals(value)) {
            return "${karaf.data}";
        }
        return null;
    }

    public Profile getOverlayProfile() {
        ProfileBuilder builder = ProfileBuilder.Factory.create("temp")
                .version(versionId)
                .addParents(profileIds);
        return profileService.getOverlayProfile(builder.getProfile());
    }

    public void initServices() throws Exception {
        runtimeProperties = createRuntimeProperties();

        if (profilesDirectory != null) {
            profileRegistry = new ReadOnlyProfileRegistry(profilesDirectory, versionId);
        } else {
            ComponentConfigurer configurer = new ComponentConfigurer();
            configurer.activate(null);

            FabricGitServiceImpl gitService = new FabricGitServiceImpl();
            if (profilesGitLocalUrl != null) {
                Git git = Git.open(new File(profilesGitLocalUrl));
                gitService.setGitForTesting(git);
            } else if (profilesGitRemoteUrl != null) {
                String remoteUrl = profilesGitRemoteUrl;
                URI uri = URI.create(profilesGitRemoteUrl);
                CredentialsProvider credsProvider = null;
                String userInfo = uri.getUserInfo();
                if (userInfo != null) {
                    String[] parts = userInfo.split(":");
                    credsProvider = new UsernamePasswordCredentialsProvider(parts[0], parts[1]);
                    remoteUrl = profilesGitRemoteUrl.replace(userInfo + "@", "");
                }

                Git git = Git.init().setDirectory(new File(gitDir)).call();
                git.commit().setMessage("First Commit").setCommitter("fabric", "user@fabric").call();
                StoredConfig config = git.getRepository().getConfig();
                config.setString("remote", "origin", "url", remoteUrl);
                config.setString("remote", "origin", "fetch", "+refs/heads/*:refs/remotes/origin/*");
                config.save();
                new DefaultPullPushPolicy(git, "origin", 300000).doPull(new GitContext(), credsProvider, true);
                gitService.setGitForTesting(git);
            }
            gitService.activateComponent();

            GitDataStoreImpl profileRegistry = new GitDataStoreImpl();
            profileRegistry.bindConfigurer(configurer);
            profileRegistry.bindRuntimeProperties(runtimeProperties);
            profileRegistry.bindGitService(gitService);
            profileRegistry.bindGitProxyService(new GitProxyRegistrationHandler());
            profileRegistry.activateComponent();
            this.profileRegistry = profileRegistry;
        }

        PermitManagerImpl permitManager = new PermitManagerImpl();
        permitManager.activate();

        ProfileServiceImpl profileService = new ProfileServiceImpl();
        profileService.bindPermitManager(permitManager);
        profileService.bindProfileRegistry(profileRegistry);
        profileService.bindRuntimeProperties(runtimeProperties);
        profileService.activate();
        this.profileService = profileService;
    }

    private RuntimeProperties createRuntimeProperties() {
        AbstractRuntimeProperties runtimeProperties = new AbstractRuntimeProperties() {
            @Override
            protected String getPropertyInternal(String key, String defaultValue) {
                if (RUNTIME_IDENTITY.equals(key)) {
                    return "root";
                } else if (RUNTIME_HOME_DIR.equals(key) || "karaf.home".equals(key)) {
                    return new File(karafHome).toString();
                } else if (RUNTIME_DATA_DIR.equals(key) || "karaf.data".equals(key)) {
                    return new File(karafHome, "data").toString();
                } else if (RUNTIME_CONF_DIR.equals(key) || "karaf.etc".equals(key)) {
                    return new File(karafHome, "etc").toString();
                } else if ("karaf.default.repository".equals(key)) {
                    return "system";
                }
                return System.getProperty(key, defaultValue);
            }
        };
        runtimeProperties.activateComponent();
        return runtimeProperties;
    }

    static class PortPlaceholderResolver implements PlaceholderResolver {
        @Override
        public String getScheme() {
            return "port";
        }

        @Override
        public String resolve(FabricService fabricService, Map<String, Map<String, String>> configs, String pid, String key, String value) {
            return "${env:" + key + " }";
        }
    }
    private static class ReadOnlyProfileRegistry implements ProfileRegistry {
        private final Version version;

        private ReadOnlyProfileRegistry(String rootDirectory, String versionId) throws Exception {
            version = loadVersion(rootDirectory, versionId, "revision");
        }

        @Override
        public Map<String, String> getDataStoreProperties() {
            throw new UnsupportedOperationException();
        }

        @Override
        public LockHandle aquireWriteLock() {
            throw new UnsupportedOperationException();
        }

        @Override
        public LockHandle aquireReadLock() {
            throw new UnsupportedOperationException();
        }

        @Override
        public String createVersion(String sourceId, String targetId, Map<String, String> attributes) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String createVersion(GitContext context, String sourceId, String targetId, Map<String, String> attributes) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String createVersion(Version version) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String createVersion(GitContext context, Version version) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<String> getVersionIds() {
            return Collections.singletonList(version.getId());
        }

        @Override
        public List<String> getVersionIds(GitContext context) {
            return getVersionIds();
        }

        @Override
        public boolean hasVersion(String versionId) {
            IllegalStateAssertion.assertNotNull(versionId, "versionId");
            return getVersionIds().contains(versionId);
        }

        @Override
        public boolean hasVersion(GitContext context, String versionId) {
            IllegalStateAssertion.assertNotNull(versionId, "versionId");
            return hasVersion(versionId);
        }

        @Override
        public Version getVersion(String versionId) {
            IllegalStateAssertion.assertNotNull(versionId, "versionId");
            return versionId.equals(version.getId()) ? version : null;
        }

        @Override
        public Version getRequiredVersion(String versionId) {
            Version version = getVersion(versionId);
            IllegalStateAssertion.assertNotNull(version, "Version does not exist: " + versionId);
            return version;
        }

        @Override
        public void deleteVersion(String versionId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void deleteVersion(GitContext context, String versionId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String createProfile(Profile profile) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String createProfile(GitContext context, Profile profile) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String updateProfile(Profile profile) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String updateProfile(GitContext context, Profile profile) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean hasProfile(String versionId, String profileId) {
            Profile profile = getProfile(versionId, profileId);
            return profile != null;
        }

        @Override
        public Profile getProfile(String versionId, String profileId) {
            IllegalStateAssertion.assertNotNull(versionId, "versionId");
            IllegalStateAssertion.assertNotNull(profileId, "profileId");
            Version version = getVersion(versionId);
            return version != null ? version.getProfile(profileId) : null;
        }

        @Override
        public Profile getRequiredProfile(String versionId, String profileId) {
            Profile profile = getProfile(versionId, profileId);
            IllegalStateAssertion.assertNotNull(profile, "Profile does not exist: " + versionId + "/" + profileId);
            return profile;
        }

        @Override
        public List<String> getProfiles(String versionId) {
            IllegalStateAssertion.assertNotNull(versionId, "versionId");
            Version version = getVersion(versionId);
            List<String> profiles = version != null ? version.getProfileIds() : Collections.<String>emptyList();
            return Collections.unmodifiableList(profiles);
        }

        @Override
        public void deleteProfile(String versionId, String profileId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void deleteProfile(GitContext context, String versionId, String profileId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void importProfiles(String versionId, List<String> profileZipUrls) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void importFromFileSystem(String importPath) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void exportProfiles(String versionId, String outputFileName, String wildcard) {
            throw new UnsupportedOperationException();
        }

        static final String CONFIGS = "fabric";
        static final String CONFIGS_PROFILES = CONFIGS + File.separator + "profiles";
        static final String VERSION_ATTRIBUTES = "version.attributes";

        private Version loadVersion(String rootDirectory, String versionId, String revision) throws Exception {
            VersionBuilder vbuilder = VersionBuilder.Factory.create(versionId).setRevision(revision);
            vbuilder.setAttributes(getVersionAttributes(rootDirectory));
            populateVersionBuilder(rootDirectory, vbuilder, versionId);
            return vbuilder.getVersion();
        }

        private Map<String, String> getVersionAttributes(String rootDirectory) throws IOException {
            File file = new File(rootDirectory, VERSION_ATTRIBUTES);
            if (!file.exists()) {
                return Collections.emptyMap();
            }
            return DataStoreUtils.toMap(Files.readBytes(file));
        }

        private void populateVersionBuilder(String rootDirectory, VersionBuilder builder, String versionId) throws GitAPIException, IOException {
            File profilesDir = new File(rootDirectory, CONFIGS_PROFILES);
            if (profilesDir.exists()) {
                String[] files = profilesDir.list();
                if (files != null) {
                    for (String childName : files) {
                        Path childPath = profilesDir.toPath().resolve(childName);
                        if (childPath.toFile().isDirectory()) {
                            populateProfile(rootDirectory, builder, versionId, childPath.toFile(), "");
                        }
                    }
                }
            }
        }

        private void populateProfile(String rootDirectory, VersionBuilder versionBuilder, String versionId, File profileFile, String prefix) throws IOException {
            String profileId = profileFile.getName();
            if (profileId.endsWith(Profiles.PROFILE_FOLDER_SUFFIX)) {
                profileId = prefix + profileId.substring(0, profileId.length() - Profiles.PROFILE_FOLDER_SUFFIX.length());
            } else {
                // lets recurse all children
                File[] files = profileFile.listFiles();
                if (files != null) {
                    for (File childFile : files) {
                        if (childFile.isDirectory()) {
                            populateProfile(rootDirectory, versionBuilder, versionId, childFile, prefix + profileFile.getName() + "-");
                        }
                    }
                }
                return;
            }

            Map<String, byte[]> fileConfigurations = doGetFileConfigurations(rootDirectory, profileId);

            ProfileBuilder profileBuilder = ProfileBuilder.Factory.create(versionId, profileId);
            profileBuilder.setFileConfigurations(fileConfigurations);
            versionBuilder.addProfile(profileBuilder.getProfile());
        }

        private Map<String, byte[]> doGetFileConfigurations(String rootDirectory, String profileId) throws IOException {
            Map<String, byte[]> configurations = new HashMap<>();
            File profilesDirectory = new File(rootDirectory, CONFIGS_PROFILES);
            String path = Profiles.convertProfileIdToPath(profileId);
            File profileDirectory = new File(profilesDirectory, path);
            populateFileConfigurations(configurations, profileDirectory, profileDirectory);
            return configurations;
        }

        private void populateFileConfigurations(Map<String, byte[]> configurations, File profileDirectory, File directory) throws IOException {
            File[] files = directory.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isFile()) {
                        String relativePath = getFilePattern(profileDirectory, file);
                        configurations.put(relativePath, loadFileConfiguration(file));
                    } else if (file.isDirectory()) {
                        populateFileConfigurations(configurations, profileDirectory, file);
                    }
                }
            }
        }

        private String getFilePattern(File rootDir, File file) throws IOException {
            String relativePath = Files.getRelativePath(rootDir, file);
            if (relativePath.startsWith(File.separator)) {
                relativePath = relativePath.substring(1);
            }
            return relativePath.replace(File.separatorChar, '/');
        }

        private byte[] loadFileConfiguration(File file) throws IOException {
            if (file.isDirectory()) {
                // Not sure why we do this, but for directory pids, lets recurse...
                StringBuilder buf = new StringBuilder();
                File[] files = file.listFiles();
                if (files != null) {
                    for (File child : files) {
                        String value = Files.toString(child);
                        buf.append(String.format("%s = %s\n", child.getName(), value));
                    }
                }
                return buf.toString().getBytes();
            } else if (file.exists() && file.isFile()) {
                return Files.readBytes(file);
            }
            return null;
        }
    }

    public static void main(String[] args) throws Exception {
        CreateJubeMojo mojo = new CreateJubeMojo();
        mojo.distribution = "org.apache.karaf:apache-karaf";

        mojo.buildDir = "target/karaf";
        mojo.profilesDirectory = "/Users/gnodet/work/apps/fabric8-karaf-1.1.0-SNAPSHOT/data/git/local/fabric";
        mojo.versionId = "1.0";
        mojo.profileIds = Arrays.asList("default", "jboss-fuse-full");
        mojo.ports = new HashMap<>();
        mojo.ports.put("io.fabric8.mq.fabric.server-broker/bindPort", "${env:ACTIVEMQ_PORT}");
        mojo.ports.put("org.ops4j.pax.web/org.osgi.service.http.port", "${env:HTTP_PORT}");

        mojo.pluginDescriptor = new PluginDescriptor();
        mojo.pluginDescriptor.setArtifacts(new ArrayList<Artifact>());
        DefaultArtifact artifact = new DefaultArtifact("org.apache.karaf", "apache-karaf", "2.4.0", "runtime", "zip", "", null);
        artifact.setFile(new File("/Users/gnodet/.m2/repository/org/apache/karaf/apache-karaf/2.4.0/apache-karaf-2.4.0.zip"));
        mojo.pluginDescriptor.getArtifacts().add(artifact);


        mojo.execute();
    }

}
