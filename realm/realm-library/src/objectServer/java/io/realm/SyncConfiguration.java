/*
 * Copyright 2016 Realm Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.realm;

import android.content.Context;

import java.io.File;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.realm.annotations.RealmModule;
import io.realm.exceptions.RealmException;
import io.realm.internal.RealmProxyMediator;
import io.realm.internal.SharedRealm;
import io.realm.rx.RealmObservableFactory;
import io.realm.rx.RxObservableFactory;

/**
 * An {@link SyncConfiguration} is used to setup a Realm that can be synchronized between devices using the Realm
 * Object Server.
 * <p>
 * A valid {@link SyncUser} is required to create a {@link SyncConfiguration}. See {@link SyncCredentials} and
 * {@link SyncUser#loginAsync(SyncCredentials, String, SyncUser.Callback)} for more information on
 * how to get a user object.
 * <p>
 * A minimal {@link SyncConfiguration} can be found below.
 * <pre>
 * {@code
 * SyncConfiguration config = new SyncConfiguration.Builder(context)
 *   .serverUrl("realm://objectserver.realm.io/~/default")
 *   .user(myUser)
 *   .build();
 * }
 * </pre>
 *
 * Synchronized Realms only support additive migrations which can be detected and performed automatically, so
 * the following builder options are not accessible compared to a normal Realm:
 *
 * <ul>
 *     <li>{@code deleteRealmIfMigrationNeeded()}</li>
 *     <li>{@code migration(Migration)}</li>
 * </ul>
 *
 * Synchronized Realms are created by using {@link Realm#getInstance(RealmConfiguration)} and
 * {@link Realm#getDefaultInstance()} like ordinary unsynchronized Realms.
 */
public class SyncConfiguration extends RealmConfiguration {

    // The FAT file system has limitations of length. Also, not all characters are permitted.
    // https://msdn.microsoft.com/en-us/library/aa365247(VS.85).aspx
    static final int MAX_FULL_PATH_LENGTH = 256;
    static final int MAX_FILE_NAME_LENGTH = 255;
    private static final char[] INVALID_CHARS = {'<', '>', ':', '"', '/', '\\', '|', '?', '*'};

    private final URI serverUrl;
    private final SyncUser user;
    private final SyncSession.ErrorHandler errorHandler;
    private final boolean deleteRealmOnLogout;
    private final boolean waitForInitialData;

    private SyncConfiguration(File directory,
                                String filename,
                                String canonicalPath,
                                String assetFilePath,
                                byte[] key,
                                long schemaVersion,
                                RealmMigration migration,
                                boolean deleteRealmIfMigrationNeeded,
                                SharedRealm.Durability durability,
                                RealmProxyMediator schemaMediator,
                                RxObservableFactory rxFactory,
                                Realm.Transaction initialDataTransaction,
                                boolean readOnly,
                                SyncUser user,
                                URI serverUrl,
                                SyncSession.ErrorHandler errorHandler,
                                boolean deleteRealmOnLogout,
                                boolean waitForInitialData

    ) {
        super(directory,
                filename,
                canonicalPath,
                assetFilePath,
                key,
                schemaVersion,
                migration,
                deleteRealmIfMigrationNeeded,
                durability,
                schemaMediator,
                rxFactory,
                initialDataTransaction,
                readOnly
        );

        this.user = user;
        this.serverUrl = serverUrl;
        this.errorHandler = errorHandler;
        this.deleteRealmOnLogout = deleteRealmOnLogout;
        this.waitForInitialData = waitForInitialData;
    }

    static URI resolveServerUrl(URI serverUrl, String userIdentifier) {
        try {
            return new URI(serverUrl.toString().replace("/~/", "/" + userIdentifier + "/"));
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Could not replace '/~/' with a valid user ID.", e);
        }
    }

    // Extract the full server path, minus the file name
    private static String getServerPath(URI serverUrl) {
        String path = serverUrl.getPath();
        int endIndex = path.lastIndexOf("/");
        if (endIndex == -1 ) {
            return path;
        } else if (endIndex == 0) {
            return path.substring(1);
        } else {
            return path.substring(1, endIndex); // Also strip leading /
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        if (!super.equals(o)) return false;

        SyncConfiguration that = (SyncConfiguration) o;

        if (deleteRealmOnLogout != that.deleteRealmOnLogout) return false;
        if (!serverUrl.equals(that.serverUrl)) return false;
        if (!user.equals(that.user)) return false;
        if (!errorHandler.equals(that.errorHandler)) return false;
        if (waitForInitialData != that.waitForInitialData) return false;
        return true;
    }

    @Override
    public int hashCode() {
        int result = super.hashCode();
        result = 31 * result + serverUrl.hashCode();
        result = 31 * result + user.hashCode();
        result = 31 * result + (deleteRealmOnLogout ? 1 : 0);
        result = 31 * result + errorHandler.hashCode();
        result = 31 * result + (waitForInitialData ? 1 : 0);
        return result;
    }

    @Override
    public String toString() {
        StringBuilder stringBuilder = new StringBuilder(super.toString());
        stringBuilder.append("\n");
        stringBuilder.append("serverUrl: " + serverUrl);
        stringBuilder.append("\n");
        stringBuilder.append("user: " + user);
        stringBuilder.append("\n");
        stringBuilder.append("errorHandler: " + errorHandler);
        stringBuilder.append("\n");
        stringBuilder.append("deleteRealmOnLogout: " + deleteRealmOnLogout);
        stringBuilder.append("\n");
        stringBuilder.append("waitForInitialRemoteData: " + waitForInitialData);
        return stringBuilder.toString();
    }

    /**
     * Returns the user.
     *
     * @return the user.
     */
    public SyncUser getUser() {
        return user;
    }

    /**
     * Returns the fully disambiguated URI for the remote Realm i.e., the {@code /~/} placeholder has been replaced
     * by the proper user ID.
     *
     * @return {@link URI} identifying the remote Realm this local Realm is synchronized with.
     */
    public URI getServerUrl() {
        return serverUrl;
    }

    public SyncSession.ErrorHandler getErrorHandler() {
        return errorHandler;
    }

    /**
     * Returns {@code true} if the Realm file must be deleted once the {@link SyncUser} owning it logs out.
     *
     * @return {@code true} if the Realm file must be deleted if the {@link SyncUser} logs out. {@code false} if the file
     *         is allowed to remain behind.
     */
    public boolean shouldDeleteRealmOnLogout() {
        return deleteRealmOnLogout;
    }


    /**
     * Returns {@code true} if the Realm will download all known changes from the remote server before being opened the
     * first time.
     *
     * @return {@code true} if all remote changes will be downloaded before the Realm can be opened. {@code false} if
     * the Realm can be opened immediately.
     */
    public boolean shouldWaitForInitialRemoteData() {
        return waitForInitialData;
    }

    @Override
    boolean isSyncConfiguration() {
        return true;
    }

    /**
     * Builder used to construct instances of a SyncConfiguration in a fluent manner.
     */
    public static final class Builder  {

        private File directory;
        private boolean overrideDefaultFolder = false;
        private String fileName;
        private boolean overrideDefaultLocalFileName = false;
        private byte[] key;
        private long schemaVersion = 0;
        private HashSet<Object> modules = new HashSet<Object>();
        private HashSet<Class<? extends RealmModel>> debugSchema = new HashSet<Class<? extends RealmModel>>();
        private RxObservableFactory rxFactory;
        private Realm.Transaction initialDataTransaction;
        private URI serverUrl;
        private SyncUser user = null;
        private SyncSession.ErrorHandler errorHandler = SyncManager.defaultSessionErrorHandler;
        private File defaultFolder;
        private String defaultLocalFileName;
        private SharedRealm.Durability durability = SharedRealm.Durability.FULL;
        private boolean deleteRealmOnLogout = false;
        private final Pattern pattern = Pattern.compile("^[A-Za-z0-9_\\-\\.]+$"); // for checking serverUrl
        private boolean readOnly = false;
        private boolean waitForServerChanges = false;

        /**
         * Creates an instance of the Builder for the SyncConfiguration.
         * <p>
         * Opening a synchronized Realm requires a valid user and an unique URI that identifies that Realm. In URIs,
         * {@code /~/} can be used as a placeholder for a user ID in case the Realm should only be available to one
         * user e.g., {@code "realm://objectserver.realm.io/~/default"}.
         * <p>
         * The URL cannot end with {@code .realm}, {@code .realm.lock} or {@code .realm.management}.
         * <p>
         * The {@code /~/} will automatically be replaced with the user ID when creating the {@link SyncConfiguration}.
         * <p>
         * Moreover, the URI defines the local location on disk. The default location of a synchronized Realm file is
         * {@code /data/data/<packageName>/files/realm-object-server/<user-id>/<last-path-segment>}, but this behavior
         * can be overwritten using {@link #name(String)} and {@link #directory(File)}.
         * <p>
         * Many Android devices are using FAT32 file systems. FAT32 file systems have a limitation that
         * file names cannot be longer than 255 characters. Moreover, the entire URI should not exceed 256 characters.
         * If file name and underlying path are too long to handle for FAT32, a shorter unique name will be generated.
         * See also @{link https://msdn.microsoft.com/en-us/library/aa365247(VS.85).aspx}.
         *
         * @param user the user for this Realm. An authenticated {@link SyncUser} is required to open any Realm managed
         *             by a Realm Object Server.
         * @param uri URI identifying the Realm. If only a path like {@code /~/default} is given, the configuration will
         *            assume the file is located on the same server returned by {@link SyncUser#getAuthenticationUrl()}.
         *
         * @see SyncUser#isValid()
         */
        public Builder(SyncUser user, String uri) {
            this(BaseRealm.applicationContext, user, uri);
        }

        Builder(Context context, SyncUser user, String url) {
            if (context == null) {
                throw new IllegalStateException("Call `Realm.init(Context)` before creating a SyncConfiguration");
            }
            this.defaultFolder = new File(context.getFilesDir(), "realm-object-server");
            if (Realm.getDefaultModule() != null) {
                this.modules.add(Realm.getDefaultModule());
            }

            validateAndSet(user);
            validateAndSet(url);
        }

        private void validateAndSet(SyncUser user) {
            if (user == null) {
                throw new IllegalArgumentException("Non-null `user` required.");
            }
            if (!user.isValid()) {
                throw new IllegalArgumentException("User not authenticated or authentication expired.");
            }
            this.user = user;
        }

        private void validateAndSet(String uri) {
            if (uri == null) {
                throw new IllegalArgumentException("Non-null 'uri' required.");
            }

            try {
                serverUrl = new URI(uri);
            } catch (URISyntaxException e) {
                throw new IllegalArgumentException("Invalid URI: " + uri, e);
            }

            try {
                // Automatically set scheme based on auth server if not set or wrongly set
                String serverScheme = serverUrl.getScheme();
                if (serverScheme == null) {
                    String authProtocol = user.getAuthenticationUrl().getProtocol();
                    if (authProtocol.equalsIgnoreCase("https")) {
                        serverScheme = "realms";
                    } else {
                        serverScheme = "realm";
                    }
                } else if (serverScheme.equalsIgnoreCase("http")) {
                    serverScheme = "realm";
                } else if (serverScheme.equalsIgnoreCase("https")) {
                    serverScheme = "realms";
                }

                // Automatically set host if one wasn't defined
                String host = serverUrl.getHost();
                if (host == null) {
                    host = user.getAuthenticationUrl().getHost();
                }

                // Convert relative paths to absolute if required
                String path = serverUrl.getPath();
                if (path != null && !path.startsWith("/")) {
                    path = "/" + path;
                }

                serverUrl = new URI(serverScheme,
                        serverUrl.getUserInfo(),
                        host,
                        serverUrl.getPort(),
                        (path != null) ? path.replace(host + "/", "") : null, // Remove host if it accidentially was interpreted as a path segment
                        serverUrl.getQuery(),
                        serverUrl.getRawFragment());

            } catch (URISyntaxException e) {
                throw new IllegalArgumentException("Invalid URI: " + uri, e);
            }

            // Detect last path segment as it is the default file name
            String path = serverUrl.getPath();
            if (path == null) {
                throw new IllegalArgumentException("Invalid URI: " + uri);
            }

            String[] pathSegments = path.split("/");
            for (int i = 1; i < pathSegments.length; i++) {
                String segment = pathSegments[i];
                if (segment.equals("~")) {
                    continue;
                }
                if (segment.equals("..") || segment.equals(".")) {
                    throw new IllegalArgumentException("The URI has an invalid segment: " + segment);
                }
                Matcher m = pattern.matcher(segment);
                if (!m.matches()) {
                    throw new IllegalArgumentException("The URI must only contain characters 0-9, a-z, A-Z, ., _, and -: " + segment);
                }
            }

            this.defaultLocalFileName = pathSegments[pathSegments.length - 1];

            // Validate filename
            // TODO Lift this restriction on the Object Server
            if (defaultLocalFileName.endsWith(".realm")
                    || defaultLocalFileName.endsWith(".realm.lock")
                    || defaultLocalFileName.endsWith(".realm.management")) {
                throw new IllegalArgumentException("The URI must not end with '.realm', '.realm.lock' or '.realm.management: " + uri);
            }
        }

        /**
         * Sets the local file name for the Realm.
         * This will override the default name defined by the Realm URL.
         *
         * @param filename name of the local file on disk.
         * @throws IllegalArgumentException if file name is {@code null} or empty.
         */
        public Builder name(String filename) {
            if (filename == null || filename.isEmpty()) {
                throw new IllegalArgumentException("A non-empty filename must be provided");
            }
            this.fileName = filename;
            this.overrideDefaultLocalFileName = true;
            return this;
        }

        /**
         * Sets the local root directory where synchronized Realm files can be saved.
         * <p>
         * Synchronized Realms will not be saved directly in the provided directory, but instead in a
         * subfolder that matches the path defined by Realm URI. As Realm server URIs are unique
         * this means that multiple users can save their Realms on disk without the risk of them overwriting
         * each other files.
         * <p>
         * The default location is {@code context.getFilesDir()}.
         *
         * @param directory directory on disk where the Realm file can be saved.
         * @throws IllegalArgumentException if the directory is not valid.
         */
        public Builder directory(File directory) {
            if (directory == null) {
                throw new IllegalArgumentException("Non-null 'directory' required.");
            }
            if (directory.isFile()) {
                throw new IllegalArgumentException("'directory' is a file, not a directory: " +
                        directory.getAbsolutePath() + ".");
            }
            if (!directory.exists() && !directory.mkdirs()) {
                throw new IllegalArgumentException("Could not create the specified directory: " +
                        directory.getAbsolutePath() + ".");
            }
            if (!directory.canWrite()) {
                throw new IllegalArgumentException("Realm directory is not writable: " +
                        directory.getAbsolutePath() + ".");
            }
            this.directory = directory;
            overrideDefaultFolder = true;
            return this;
        }

        /**
         * Sets the {@value io.realm.RealmConfiguration#KEY_LENGTH} bytes key used to encrypt and decrypt the Realm file.
         *
         * @param key the encryption key.
         * @throws IllegalArgumentException if key is invalid.
         */
        public Builder encryptionKey(byte[] key) {
            if (key == null) {
                throw new IllegalArgumentException("A non-null key must be provided");
            }
            if (key.length != KEY_LENGTH) {
                throw new IllegalArgumentException(String.format("The provided key must be %s bytes. Yours was: %s",
                        KEY_LENGTH, key.length));
            }
            this.key = Arrays.copyOf(key, key.length);
            return this;
        }

        /**
         * DEBUG method. This restricts the Realm schema to only consist of the provided classes without having to
         * create a module. These classes must be available in the default module. Calling this will remove any
         * previously configured modules.
         */
        SyncConfiguration.Builder schema(Class<? extends RealmModel> firstClass, Class<? extends RealmModel>... additionalClasses) {
            if (firstClass == null) {
                throw new IllegalArgumentException("A non-null class must be provided");
            }
            modules.clear();
            modules.add(DEFAULT_MODULE_MEDIATOR);
            debugSchema.add(firstClass);
            if (additionalClasses != null) {
                Collections.addAll(debugSchema, additionalClasses);
            }

            return this;
        }

        /**
         * Sets the schema version of the Realm.
         * <p>
         * Synced Realms only support additive schema changes which can be applied without requiring a manual
         * migration. The schema version will only be used as an indication to the underlying storage layer to remove
         * or add indexes. These will be recalculated if the provided schema version differ from the version in the
         * Realm file.
         *
         * <b>WARNING:</b> There is no guarantee that the value inserted here is the same returned by {@link Realm#getVersion()}.
         * Due to the nature of synced Realms, the value can both be higher and lower.
         * <ul>
         *     <li>It will be lower if another client with a lesser {@code schemaVersion} connected to the server for
         *         the first time after this schemaVersion was used.
     *         </li>
         *     <li>It will be higher if another client with a higher {@code schemaVersion} connected to the server after
         *         this Realm was created.
     *         </li>
         * </ul>
         *
         * @param schemaVersion the schema version.
         * @throws IllegalArgumentException if schema version is invalid.
         */
        public Builder schemaVersion(long schemaVersion) {
            if (schemaVersion < 0) {
                throw new IllegalArgumentException("Realm schema version numbers must be 0 (zero) or higher. Yours was: " + schemaVersion);
            }
            this.schemaVersion = schemaVersion;
            return this;
        }

        /**
         * Replaces the existing module(s) with one or more {@link RealmModule}s. Using this method will replace the
         * current schema for this Realm with the schema defined by the provided modules.
         * <p>
         * A reference to the default Realm module containing all Realm classes in the project (but not dependencies),
         * can be found using {@link Realm#getDefaultModule()}. Combining the schema from the app project and a library
         * dependency is thus done using the following code:
         * <p>
         * {@code builder.modules(Realm.getDefaultMode(), new MyLibraryModule()); }
         * <p>
         * @param baseModule the first Realm module (required).
         * @param additionalModules the additional Realm modules
         * @throws IllegalArgumentException if any of the modules don't have the {@link RealmModule} annotation.
         * @see Realm#getDefaultModule()
         */
        public Builder modules(Object baseModule, Object... additionalModules) {
            modules.clear();
            addModule(baseModule);
            if (additionalModules != null) {
                for (Object module : additionalModules) {
                    addModule(module);
                }
            }
            return this;
        }

        /**
         * Sets the {@link RxObservableFactory} used to create Rx Observables from Realm objects.
         * The default factory is {@link RealmObservableFactory}.
         *
         * @param factory factory to use.
         */
        public Builder rxFactory(RxObservableFactory factory) {
            rxFactory = factory;
            return this;
        }

        /**
         * Sets the initial data in {@link io.realm.Realm}. This transaction will be executed only the first time
         * the Realm file is opened (created) or while migrating the data if
         * {@link RealmConfiguration.Builder#deleteRealmIfMigrationNeeded()} is set.
         *
         * @param transaction transaction to execute.
         */
        public Builder initialData(Realm.Transaction transaction) {
            initialDataTransaction = transaction;
            return this;
        }

        /**
         * Setting this will create an in-memory Realm instead of saving it to disk. In-memory Realms might still use
         * disk space if memory is running low, but all files created by an in-memory Realm will be deleted when the
         * Realm is closed.
         * <p>
         * Note that because in-memory Realms are not persisted, you must be sure to hold on to at least one non-closed
         * reference to the in-memory Realm object with the specific name as long as you want the data to last.
         */
        public Builder inMemory() {
            this.durability = SharedRealm.Durability.MEM_ONLY;
            return this;
        }

        /**
         * Sets the error handler used by this configuration. This will override any handler set by calling
         * {@link SyncManager#setDefaultSessionErrorHandler(SyncSession.ErrorHandler)}.
         * <p>
         * Only errors not handled by the defined {@code SyncPolicy} will be reported to this error handler.
         *
         * @param errorHandler error handler used to report back errors when communicating with the Realm Object Server.
         * @throws IllegalArgumentException if {@code null} is given as an error handler.
         */
        public Builder errorHandler(SyncSession.ErrorHandler errorHandler) {
            if (errorHandler == null) {
                throw new IllegalArgumentException("Non-null 'errorHandler' required.");
            }
            this.errorHandler = errorHandler;
            return this;
        }

        /**
         * Setting this will cause the Realm to download all known changes from the server the first time a Realm is
         * opened. The Realm will not open until all the data has been downloaded. This means that if a device is
         * offline the Realm will not open.
         * <p>
         * Since downloading all changes can be an lengthy operation that might block the UI thread, Realms with this
         * setting enabled should only be opened on background threads or with
         * {@link Realm#getInstanceAsync(RealmConfiguration, Realm.Callback)} on the UI thread.
         * <p>
         * This check is only enforced the first time a Realm is created. If you otherwise want to make sure a Realm
         * has the latest changes, use {@link SyncSession#downloadAllServerChanges()}.
         */
        public Builder waitForInitialRemoteData() {
            this.waitForServerChanges = true;
            return this;
        }

        /**
         * Setting this will cause the Realm to become read only and all write transactions made against this Realm will
         * fail with an {@link IllegalStateException}.
         * <p>
         * This in particular mean that {@link #initialData(Realm.Transaction)} will not work in combination with a
         * read only Realm and setting this will result in a {@link IllegalStateException} being thrown.
         * </p>
         * Marking a Realm as read only only applies to the Realm in this process. Other processes and devices can still
         * write to the Realm.
         */
        public SyncConfiguration.Builder readOnly() {
            this.readOnly = true;
            return this;
        }

        private String MD5(String in) {
            try {
                MessageDigest digest = MessageDigest.getInstance("MD5");
                byte[] buf = digest.digest(in.getBytes("UTF-8"));
                StringBuilder builder = new StringBuilder();
                for (byte b : buf) {
                    builder.append(String.format("%02X", b));
                }
                return builder.toString();
            } catch (NoSuchAlgorithmException e) {
                throw new RealmException(e.getMessage());
            } catch (UnsupportedEncodingException e) {
                throw new RealmException(e.getMessage());
            }
        }

        /**
         * Setting this will cause the local Realm file used to synchronize changes to be deleted if the {@link SyncUser}
         * owning this Realm logs out from the device using {@link SyncUser#logout()}.
         * <p>
         * The default behavior is that the Realm file is allowed to stay behind, making it possible for users to log
         * in again and have access to their data faster.
         */
        /* FIXME: Disable this API since we cannot support it without https://github.com/realm/realm-core/issues/2165
        public Builder deleteRealmOnLogout() {
            this.deleteRealmOnLogout = true;
            return this;
        }
        */

        /**
         * Creates the RealmConfiguration based on the builder parameters.
         *
         * @return the created {@link SyncConfiguration}.
         * @throws IllegalStateException if the configuration parameters are invalid or inconsistent.
         */
        public SyncConfiguration build() {
            if (serverUrl == null || user == null) {
                throw new IllegalStateException("serverUrl() and user() are both required.");
            }

            // Check that readOnly() was applied to legal configuration. Right now it should only be allowd if
            // an assetFile is configured
            if (readOnly) {
                if (initialDataTransaction != null) {
                    throw new IllegalStateException("This Realm is marked as read-only. " +
                            "Read-only Realms cannot use initialData(Realm.Transaction).");
                }
                if (!waitForServerChanges) {
                    throw new IllegalStateException("A read-only Realms must be provided by some source. " +
                            "'waitForInitialRemoteData()' wasn't enabled which is currently the only supported source.");
                }
            }

            // Check if the user has an identifier, if not, it cannot use /~/.
            if (serverUrl.toString().contains("/~/") && user.getIdentity() == null) {
                throw new IllegalStateException("The serverUrl contains a /~/, but the user does not have an identity." +
                        " Most likely it hasn't been authenticated yet or has been created directly from an" +
                        " access token. Use a path without /~/.");
            }

            if (rxFactory == null && isRxJavaAvailable()) {
                rxFactory = new RealmObservableFactory();
            }

            // Determine location on disk
            // Use the serverUrl + user to create a unique filepath unless it has been explicitly overridden.
            // <rootDir>/<userIdentifier>/<serverPath>/<serverFileNameOrOverriddenFileName>
            URI resolvedServerUrl = resolveServerUrl(serverUrl, user.getIdentity());
            File rootDir = overrideDefaultFolder ? directory : defaultFolder;
            String realmPathFromRootDir = user.getIdentity() + "/" + getServerPath(resolvedServerUrl);
            File realmFileDirectory = new File(rootDir, realmPathFromRootDir);

            String realmFileName = overrideDefaultLocalFileName ? fileName : defaultLocalFileName;
            String fullPathName = realmFileDirectory.getAbsolutePath() + File.pathSeparator + realmFileName;
            // full path must not exceed 256 characters (on FAT)
            if (fullPathName.length() > MAX_FULL_PATH_LENGTH) {
                // path is too long, so we make the file name shorter
                realmFileName = MD5(realmFileName);
                fullPathName = realmFileDirectory.getAbsolutePath() + File.pathSeparator + realmFileName;
                if (fullPathName.length() > MAX_FULL_PATH_LENGTH) {
                    // use rootDir/userIdentify as directory instead as it is shorter
                    realmFileDirectory = new File(rootDir, user.getIdentity());
                    fullPathName = realmFileDirectory.getAbsolutePath() + File.pathSeparator + realmFileName;
                    if (fullPathName.length() > MAX_FULL_PATH_LENGTH) { // we are out of ideas
                        throw new IllegalStateException(String.format("Full path name must not exceed %d characters: %s",
                                MAX_FULL_PATH_LENGTH, fullPathName));
                    }
                }
            }

            if (realmFileName.length() > MAX_FILE_NAME_LENGTH) {
                throw new IllegalStateException(String.format("File name exceed %d characters: %d", MAX_FILE_NAME_LENGTH,
                        realmFileName.length()));
            }

            // substitute invalid characters
            for (char c : INVALID_CHARS) {
                realmFileName = realmFileName.replace(c, '_');
            }

            // Create the folder on disk (if needed)
            if (!realmFileDirectory.exists() && !realmFileDirectory.mkdirs()) {
                throw new IllegalStateException("Could not create directory for saving the Realm: " + realmFileDirectory);
            }

            return new SyncConfiguration(
                    // Realm Configuration options
                    realmFileDirectory,
                    realmFileName,
                    getCanonicalPath(new File(realmFileDirectory, realmFileName)),
                    null, // assetFile not supported by Sync. See https://github.com/realm/realm-sync/issues/241
                    key,
                    schemaVersion,
                    null, // Custom migrations not supported
                    false, // MigrationNeededException is never thrown
                    durability,
                    createSchemaMediator(modules, debugSchema),
                    rxFactory,
                    initialDataTransaction,
                    readOnly,

                    // Sync Configuration specific
                    user,
                    resolvedServerUrl,
                    errorHandler,
                    deleteRealmOnLogout,
                    waitForServerChanges
            );
        }

        private void addModule(Object module) {
            if (module != null) {
                checkModule(module);
                modules.add(module);
            }
        }

        private void checkModule(Object module) {
            if (!module.getClass().isAnnotationPresent(RealmModule.class)) {
                throw new IllegalArgumentException(module.getClass().getCanonicalName() + " is not a RealmModule. " +
                        "Add @RealmModule to the class definition.");
            }
        }
    }
}
