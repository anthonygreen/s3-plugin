package hudson.plugins.s3;

import com.amazonaws.AmazonServiceException;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.s3.AmazonS3Client;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import hudson.*;
import hudson.model.*;
import hudson.model.listeners.RunListener;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Fingerprinter.FingerprintAction;
import hudson.tasks.Publisher;
import hudson.tasks.Recorder;
import hudson.util.CopyOnWriteList;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import jenkins.model.Jenkins;
import jenkins.tasks.SimpleBuildStep;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import org.apache.commons.lang.StringUtils;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.io.PrintStream;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class S3BucketPublisher extends Recorder implements SimpleBuildStep {

    private String profileName;
    @Extension
    public static final DescriptorImpl DESCRIPTOR = new DescriptorImpl();

    private final List<Entry> entries;

    private boolean dontWaitForConcurrentBuildCompletion;

    /**
     * User metadata key/value pairs to tag the upload with.
     */
    private /*almost final*/ List<MetadataPair> userMetadata;

    @DataBoundConstructor
    public S3BucketPublisher(String profileName, List<Entry> entries, List<MetadataPair> userMetadata,
                             boolean dontWaitForConcurrentBuildCompletion) {
        if (profileName == null) {
            // defaults to the first one
            final S3Profile[] sites = DESCRIPTOR.getProfiles();
            if (sites.length > 0)
                profileName = sites[0].getName();
        }

        this.profileName = profileName;
        this.entries = entries;

        if (userMetadata == null)
            userMetadata = new ArrayList<>();
        this.userMetadata = userMetadata;

        this.dontWaitForConcurrentBuildCompletion = dontWaitForConcurrentBuildCompletion;
    }

    protected Object readResolve() {
        if (userMetadata == null)
            userMetadata = new ArrayList<>();
        return this;
    }

    @SuppressWarnings("unused")
    public List<Entry> getEntries() {
        return entries;
    }

    @SuppressWarnings("unused")
    public List<MetadataPair> getUserMetadata() {
        return userMetadata;
    }

    @SuppressWarnings("unused")
    public String getProfileName() {
        return this.profileName;
    }

    @SuppressWarnings("unused")
    public boolean isDontWaitForConcurrentBuildCompletion() {
        return dontWaitForConcurrentBuildCompletion;
    }

    public S3Profile getProfile() {
        return getProfile(profileName);
    }

    public static S3Profile getProfile(String profileName) {
        final S3Profile[] profiles = DESCRIPTOR.getProfiles();

        if (profileName == null && profiles.length > 0)
            // default
            return profiles[0];

        for (S3Profile profile : profiles) {
            if (profile.getName().equals(profileName))
                return profile;
        }
        return null;
    }

    @Override
    public Collection<? extends Action> getProjectActions(AbstractProject<?, ?> project) {
        return ImmutableList.of(new S3ArtifactsProjectAction(project));
    }

    private void log(final PrintStream logger, final String message) {
        logger.println(StringUtils.defaultString(getDescriptor().getDisplayName()) + ' ' + message);
    }

    @Override
    public void perform(@Nonnull Run<?, ?> run, @Nonnull FilePath ws, @Nonnull Launcher launcher, @Nonnull TaskListener listener)
            throws InterruptedException {
        final PrintStream console = listener.getLogger();
        if (Result.ABORTED.equals(run.getResult())) {
            log(console, "Skipping publishing on S3 because build aborted");
            return;
        }

        if (run.isBuilding()) {
            log(console, "Build is still running");
        }

        final S3Profile profile = getProfile();

        if (profile == null) {
            log(console, "No S3 profile is configured.");
            run.setResult(Result.UNSTABLE);
            return;
        }


        log(console, "Using S3 profile: " + profile.getName());

        try {
            final Map<String, String> envVars = run.getEnvironment(listener);
            final Map<String, String> record = Maps.newHashMap();
            final List<FingerprintRecord> artifacts = Lists.newArrayList();

            for (Entry entry : entries) {
                if (entry.noUploadOnFailure && Result.FAILURE.equals(run.getResult())) {
                    // build failed. don't post
                    log(console, "Skipping publishing on S3 because build failed");
                    continue;
                }

                final String expanded = Util.replaceMacro(entry.sourceFile, envVars);
                final String exclude = Util.replaceMacro(entry.excludedFile, envVars);
                if (expanded == null) {
                    throw new IOException();
                }

                final String bucket = Util.replaceMacro(entry.bucket, envVars);
                final String storageClass = Util.replaceMacro(entry.storageClass, envVars);
                final String selRegion = entry.selectedRegion;

                final List<FilePath> paths = new ArrayList<>();
                final List<String> filenames = new ArrayList<>();

                for (String startPath : expanded.split(",")) {
                    for (FilePath path : ws.list(startPath, exclude)) {
                        if (path.isDirectory()) {
                            throw new IOException(path + " is a directory");
                        }

                        paths.add(path);
                        final int workspacePath = FileHelper.getSearchPathLength(ws.getRemote(),
                                startPath.trim(),
                                getProfile().isKeepStructure());
                        filenames.add(getFilename(path, entry.flatten, workspacePath));
                        log(console, "bucket=" + bucket + ", file=" + path.getName() + " region=" + selRegion + ", will be uploaded from slave=" + entry.uploadFromSlave + " managed=" + entry.managedArtifacts + " , server encryption " + entry.useServerSideEncryption);
                    }
                }

                if (paths.isEmpty()) {
                    // try to do error diagnostics
                    log(console, "No file(s) found: " + expanded);
                    try {
                        final String error = ws.validateAntFileMask(expanded, 100);
                        if (error != null) {
                            log(console, error);
                        }
                    } catch (InterruptedException ignored) {
                        // don't want to die here just because
                        // validateAntFileMask found no alternative paths within
                        // alloted bounds limit
                    } finally {
                        continue;
                    }
                }


                final Map<String, String> escapedMetadata = buildMetadata(envVars, entry);

                final List<FingerprintRecord> records = Lists.newArrayList();
                final List<FingerprintRecord> fingerprints = profile.upload(run, bucket, paths, filenames, escapedMetadata, storageClass, selRegion, entry.uploadFromSlave, entry.managedArtifacts, entry.useServerSideEncryption, entry.gzipFiles);

                for (FingerprintRecord fingerprintRecord : fingerprints) {
                    records.add(fingerprintRecord);
                    fingerprintRecord.setKeepForever(entry.keepForever);
                }

                if (entry.managedArtifacts) {
                    artifacts.addAll(fingerprints);
                    fillFingerprints(run, listener, record, fingerprints);
                }
            }

            // don't bother adding actions if none of the artifacts are managed
            if (!artifacts.isEmpty()) {
                run.addAction(new S3ArtifactsAction(run, profile, artifacts));
                run.addAction(new FingerprintAction(run, record));
            }
        } catch (IOException e) {
            e.printStackTrace(listener.error("Failed to upload files"));
            run.setResult(Result.UNSTABLE);
        }
    }

    private void fillFingerprints(@Nonnull Run<?, ?> run, @Nonnull TaskListener listener, Map<String, String> record, List<FingerprintRecord> fingerprints) throws IOException {
        for (FingerprintRecord r : fingerprints) {
            final Fingerprint fp = r.addRecord(run);
            if (fp == null) {
                listener.error("Fingerprinting failed for " + r.getName());
                continue;
            }
            fp.addFor(run);
            record.put(r.getName(), fp.getHashString());
        }
    }

    private Map<String, String> buildMetadata(Map<String, String> envVars, Entry entry) {
        final Map<String, String> mergedMetadata = new HashMap<>();

        if (userMetadata != null) {
            for (MetadataPair pair : userMetadata) {
                mergedMetadata.put(pair.key, pair.value);
            }
        }

        if (entry.userMetadata != null) {
            for (MetadataPair pair : entry.userMetadata) {
                mergedMetadata.put(pair.key, pair.value);
            }
        }

        final Map<String, String> escapedMetadata = new HashMap<>();

        for (Map.Entry<String, String> mapEntry : mergedMetadata.entrySet()) {
            escapedMetadata.put(
                    Util.replaceMacro(mapEntry.getKey(), envVars),
                    Util.replaceMacro(mapEntry.getValue(), envVars));
        }

        return escapedMetadata;
    }

    private String getFilename(FilePath src, boolean flatten, int searchIndex) {
        final String fileName;
        if (flatten) {
            fileName = src.getName();
        } else {
            final String relativeFileName = src.getRemote();
            fileName = relativeFileName.substring(searchIndex);
        }
        return fileName;
    }

    @Extension
    public static final class S3DeletedJobListener extends RunListener<Run> {
        @Override
        public void onDeleted(Run run) {
            final S3ArtifactsAction artifacts = run.getAction(S3ArtifactsAction.class);
            if (artifacts != null) {
                final S3Profile profile = S3BucketPublisher.getProfile(artifacts.getProfile());
                for (FingerprintRecord record : artifacts.getArtifacts()) {
                    if (!record.isKeepForever()) {
                        profile.delete(run, record);
                    }
                }
            }
        }
    }

    public BuildStepMonitor getRequiredMonitorService() {
        return dontWaitForConcurrentBuildCompletion ? BuildStepMonitor.NONE : BuildStepMonitor.STEP;
    }

    public static final class DescriptorImpl extends BuildStepDescriptor<Publisher> {

        private final CopyOnWriteList<S3Profile> profiles = new CopyOnWriteList<S3Profile>();
        private static final Logger LOGGER = Logger.getLogger(DescriptorImpl.class.getName());

        public DescriptorImpl(Class<? extends Publisher> clazz) {
            super(clazz);
            load();
        }

        public Regions[] regions = Entry.regions;

        public String[] storageClasses = Entry.storageClasses;

        public DescriptorImpl() {
            this(S3BucketPublisher.class);
        }

        @Override
        public String getDisplayName() {
            return "Publish artifacts to S3 Bucket";
        }

        @Override
        public String getHelpFile() {
            return "/plugin/s3/help.html";
        }

        @Override
        public boolean configure(StaplerRequest req, JSONObject json) {
            final JSONArray array = json.optJSONArray("profile");
            if (array != null) {
                profiles.replaceBy(req.bindJSONToList(S3Profile.class, array));
            } else {
                profiles.replaceBy(req.bindJSON(S3Profile.class, json.getJSONObject("profile")));
            }
            save();
            return true;
        }

        @SuppressWarnings("unused")
        public ListBoxModel doFillProfileNameItems() {
            final ListBoxModel model = new ListBoxModel();
            for (S3Profile profile : profiles) {
                model.add(profile.getName(), profile.getName());
            }
            return model;
        }

        public S3Profile[] getProfiles() {
            final S3Profile[] profileArray = new S3Profile[profiles.size()];
            return profiles.toArray(profileArray);
        }

        @SuppressWarnings("unused")
        public FormValidation doLoginCheck(final StaplerRequest req, StaplerResponse rsp) {
            final String name = Util.fixNull(req.getParameter("name"));
            final String accessKey = Util.fixNull(req.getParameter("accessKey"));
            final String secretKey = Util.fixNull(req.getParameter("secretKey"));
            final String useIAMCredential = Util.fixNull(req.getParameter("useRole"));

            final boolean couldBeValidated = !name.isEmpty() && !accessKey.isEmpty() && !secretKey.isEmpty();
            final boolean useRole = Boolean.parseBoolean(useIAMCredential);

            if (!couldBeValidated) {
                if (name.isEmpty())
                    return FormValidation.ok("Please, enter name");

                if (useRole)
                    return FormValidation.ok();

                if (accessKey.isEmpty())
                    return FormValidation.ok("Please, enter accessKey");

                if (secretKey.isEmpty())
                    return FormValidation.ok("Please, enter secretKey");
            }

            final AmazonS3Client client = ClientHelper.createClient(accessKey, secretKey, useRole, Jenkins.getActiveInstance().proxy);

            try {
                client.listBuckets();
            } catch (AmazonServiceException e) {
                LOGGER.log(Level.SEVERE, e.getMessage(), e);
                return FormValidation.error("Can't connect to S3 service: " + e.getMessage());
            }
            return FormValidation.ok("Check passed!");
        }

        @Override
        public boolean isApplicable(Class<? extends AbstractProject> aClass) {
            return true;
        }
    }
}
