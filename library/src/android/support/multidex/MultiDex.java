/*
 * Copyright (C) 2013 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.support.multidex;

import android.app.Application;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Build;
import android.support.multidex.handler.exception.ExceptionHandler;
import android.support.multidex.handler.exception.impl.NoSpaceLeftOnDeviceHandler;
import android.support.multidex.handler.exception.impl.ReadOnlySystemHandle;
import android.text.TextUtils;
import android.util.Log;
import android.util.SparseArray;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.ListIterator;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;

import dalvik.system.DexClassLoader;
import dalvik.system.DexFile;

/**
 * MultiDex patches {@link Context#getClassLoader() the application context class
 * loader} in order to load classes from more than one dex file. The primary
 * {@code classes.dex} must contain the classes necessary for calling this
 * class methods. Secondary dex files named classes2.dex, classes3.dex... found
 * in the application apk will be added to the classloader after first call to
 * {@link #install(Context)}.
 * <p>
 * <p/>
 * This library provides compatibility for platforms with API level 4 through 20. This library does
 * nothing on newer versions of the platform which provide built-in support for secondary dex files.
 */
public final class MultiDex {

    static final String TAG = "MultiDex";

    private static final String OLD_SECONDARY_FOLDER_NAME = "secondary-dexes";

    private static final String CODE_CACHE_NAME = "code_cache";

    public static final String CODE_CACHE_SECONDARY_FOLDER_NAME = "secondary-dexes";

    private static final int MAX_SUPPORTED_SDK_VERSION = 20;

    private static final int MIN_SDK_VERSION = 4;

    private static final int VM_WITH_MULTIDEX_VERSION_MAJOR = 2;

    private static final int VM_WITH_MULTIDEX_VERSION_MINOR = 1;

    private static final String NO_KEY_PREFIX = "";

    private static final Set<File> installedApk = new HashSet<File>();

    public static final boolean IS_VM_MULTIDEX_CAPABLE =
            isVMMultidexCapable(System.getProperty("java.vm.version"));

    private static final String FIELD_NAME_PATH_LIST = "pathList";

    /**
     * test mode: Delete cached dex file and zip file every time when install method called
     */
    public static boolean testMode = false;


    /**
     * Use parallel mode when core number of CPU was lg 2
     */
    public static final int MODE_AUTO = 1 << 31;

    /**
     * Unpacking parallel
     */
    public static final int MODE_EXTRACT_PARALLEL = 1;

    /**
     * dex opt parallel
     */
    public static final int MODE_DEX_OPT_PARALLEL = 1 << 1;

    /**
     * Unpacking and dex opt serial
     */
    public static final int MODE_SERIAL = 0;

    /**
     * Unpacking and dex opt parallel
     */
    public static final int MODE_PARALLEL = MODE_EXTRACT_PARALLEL | MODE_DEX_OPT_PARALLEL;

    public interface Logger {
        void log(String msg);
    }

    private static Logger logger;

    /**
     * use lock
     */
    public static boolean useLock = true;

    private static SparseArray<Object> originalDexElements = new SparseArray<>();

    private static ArrayList<ExceptionHandler> handlers = new ArrayList<>();

    static {
        handlers.add(new ReadOnlySystemHandle());
        handlers.add(new NoSpaceLeftOnDeviceHandler());
    }

    private MultiDex() {
    }

    /**
     * Patches the application context class loader by appending extra dex files
     * loaded from the application apk. This method should be called in the
     * attachBaseContext of your {@link Application}, see
     * {@link MultiDexApplication} for more explanation and an example.
     *
     * @param context application context.
     * @throws RuntimeException if an error occurred preventing the classloader
     *                          extension.
     */
    public static void install(Context context) {
        install(context, MODE_AUTO);
    }

    /**
     * Patches the application context class loader by appending extra dex files
     * loaded from the application apk. This method should be called in the
     * attachBaseContext of your {@link Application}, see
     * {@link MultiDexApplication} for more explanation and an example.
     *
     * @param context application context.
     * @param mode    execute mode {@link MultiDex#MODE_SERIAL}, {@link MultiDex#MODE_PARALLEL}, {@link MultiDex#MODE_AUTO}
     * @throws RuntimeException if an error occurred preventing the classloader
     *                          extension.
     */
    public static void install(Context context, int mode, String... classNames) {
        log("Installing application");
        if (IS_VM_MULTIDEX_CAPABLE) {
            log("VM has multidex support, MultiDex support library is disabled.");
            return;
        }

        if (Build.VERSION.SDK_INT < MIN_SDK_VERSION) {
            throw new RuntimeException("MultiDex installation failed. SDK " + Build.VERSION.SDK_INT
                    + " is unsupported. Min SDK version is " + MIN_SDK_VERSION + ".");
        }

        ApplicationInfo applicationInfo = getApplicationInfo(context);
        if (applicationInfo == null) {
            log("No ApplicationInfo available, i.e. running on a test Context:"
                    + " MultiDex support library is disabled.");
            return;
        }

        try {
            doInstallation(context,
                    new File(applicationInfo.sourceDir),
                    new File(applicationInfo.dataDir),
                    CODE_CACHE_SECONDARY_FOLDER_NAME,
                    NO_KEY_PREFIX, mode, classNames);

        } catch (Exception e) {
            String msg = Log.getStackTraceString(e);
            log("MultiDex installation failure, " + msg);
            boolean handled = false;
            for (ExceptionHandler handler : handlers) {
                if (handler.handle(context, e, msg)) {
                    handled = true;
                }
            }
            if (handled) {
                try {
                    restore(context.getClassLoader());
                    doInstallation(context,
                            new File(applicationInfo.sourceDir),
                            new File(applicationInfo.dataDir),
                            CODE_CACHE_SECONDARY_FOLDER_NAME,
                            NO_KEY_PREFIX, mode, classNames);
                } catch (Exception e1) {
                    throw new RuntimeException("MultiDex retry installation failed (" + msg + ").", e1);
                }
            } else {
                throw new RuntimeException("MultiDex installation failed (" + msg + ").");
            }
        }
        log("install done");
    }


    public static void installWithExceptionHandle(Context context, Callable runnable) {
        try {
            runnable.call();
        } catch (Exception e) {
            String msg = Log.getStackTraceString(e);
            log("MultiDex installation failure, " + msg);
            boolean handled = false;
            for (ExceptionHandler handler : handlers) {
                if (handler.handle(context, e, msg)) {
                    handled = true;
                }
            }
            if (handled) {
                try {
                    runnable.call();
                } catch (Exception e1) {
                    throw new RuntimeException("MultiDex retry installation failed (" + msg + ").", e1);
                }
            } else {
                throw new RuntimeException("MultiDex installation failed (" + msg + ").");
            }
        }
        log("install done");
    }

    /**
     * restore classLoader
     *
     * @param classLoader
     */
    public static void restore(ClassLoader classLoader) throws NoSuchFieldException, IllegalAccessException {
        Object originalDexElement = originalDexElements.get(classLoader.hashCode());
        if (originalDexElement != null) {
            Object dexPathList = getFieldValue(classLoader, FIELD_NAME_PATH_LIST);
            Field dexElementsField = findField(dexPathList, "dexElements");
            log("before restore " + classLoader);
            dexElementsField.set(dexPathList, originalDexElement);
            log("after restore " + classLoader);
        }
    }

    /**
     * @param mainContext         context used to get filesDir, to save preference and to get the
     *                            classloader to patch.
     * @param sourceApk           Apk file.
     * @param dataDir             data directory to use for code cache simulation.
     * @param secondaryFolderName name of the folder for storing extractions.
     * @param prefsKeyPrefix      prefix of all stored preference keys.
     * @param mode
     */
    private static void doInstallation(Context mainContext, File sourceApk, File dataDir, String secondaryFolderName,
                                       String prefsKeyPrefix, int mode, final String... classNames) throws IOException,
            IllegalArgumentException, IllegalAccessException, NoSuchFieldException,
            InvocationTargetException, NoSuchMethodException {
        synchronized (installedApk) {

            if (!testMode) {
                if (installedApk.contains(sourceApk)) {
                    return;
                }
            }

            if (Build.VERSION.SDK_INT > MAX_SUPPORTED_SDK_VERSION) {
                log("MultiDex is not guaranteed to work in SDK version "
                        + Build.VERSION.SDK_INT + ": SDK version higher than "
                        + MAX_SUPPORTED_SDK_VERSION + " should be backed by "
                        + "runtime with built-in multidex capabilty but it's not the "
                        + "case here: java.vm.version=\""
                        + System.getProperty("java.vm.version") + "\"");
            }

            /* The patched class loader is expected to be a descendant of
             * dalvik.system.BaseDexClassLoader. We modify its
             * dalvik.system.DexPathList pathList field to append additional DEX
             * file entries.
             */
            final ClassLoader loader;
            try {
                loader = mainContext.getClassLoader();
            } catch (RuntimeException e) {
                /* Ignore those exceptions so that we don't break tests relying on Context like
                 * a android.test.mock.MockContext or a android.content.ContextWrapper with a
                 * null base Context.
                 */
                log("Failure while trying to obtain Context class loader. " +
                        "Must be running in test mode. Skip patching.", e);
                return;
            }
            if (loader == null) {
                // Note, the context class loader is null when running Robolectric tests.
                log(
                        "Context class loader is null. Must be running in test mode. "
                                + "Skip patching.");
                return;
            }

            try {
                clearOldDexDir(mainContext);
            } catch (Throwable t) {
                log("Something went wrong when trying to clear old MultiDex extraction, "
                        + "continuing without cleaning.", t);
            }

            final File dexDir = getDexDir(mainContext, dataDir, secondaryFolderName);
            if (testMode) {
                //Delete cached classesN.zip and classesN.dex
                long start = System.currentTimeMillis();
                File[] files = dexDir.listFiles();
                if (files != null) {
                    for (File file : files) {
                        file.delete();
                    }
                }
                log("Delete cached file  classesN.zip and classesN.dex " + (System.currentTimeMillis() - start) + "ms");
            }

            if ((mode & MODE_AUTO) == MODE_AUTO) {
                int count = Runtime.getRuntime()
                                   .availableProcessors();
                mode = count > 1 ? MODE_PARALLEL : MODE_SERIAL;
                log("CUP core number is " + count + ", choose mode " + (count > 1 ? "MODE_PARALLEL" : "MODE_SERIAL"));
            }
            List<? extends File> files = Collections.emptyList();
            log("Use mode " + Integer.toBinaryString(mode) + " to load extract and dex opt");
            if (mode == MODE_SERIAL) {
                files = MultiDexExtractor.load(mainContext, sourceApk, dexDir, prefsKeyPrefix, false || testMode, null);
                installSecondaryDexes(loader, dexDir, files);
            } else if ((mode & MODE_EXTRACT_PARALLEL) == MODE_EXTRACT_PARALLEL) {
                final int finalMode = mode;
                files = MultiDexExtractor.load(mainContext, sourceApk, dexDir, prefsKeyPrefix, false || testMode, new MultiDexExtractor.DexAsyncHandler() {
                    @Override
                    public void handle(File dexFile) throws Exception {
                        if ((finalMode & MODE_DEX_OPT_PARALLEL) == MODE_DEX_OPT_PARALLEL) {
                            long start = System.currentTimeMillis();
                            new DexClassLoader(dexFile.getAbsolutePath(), dexDir.getAbsolutePath(), "", loader);
                            log("Dex opt success " + (System.currentTimeMillis() - start) + "ms " + dexFile);
                        } else {
                            synchronized (MultiDex.class) {
                                long start = System.currentTimeMillis();
                                new DexClassLoader(dexFile.getAbsolutePath(), dexDir.getAbsolutePath(), "", loader);
                                log("Dex opt success " + (System.currentTimeMillis() - start) + "ms " + dexFile);
                            }
                        }
                    }
                });
                installSecondaryDexes(loader, dexDir, files);
            }
            if (!files.isEmpty() && classNames.length > 0) {
                try {
                    for (String className : classNames) {
                        loader.loadClass(className);
                    }
                } catch (ClassNotFoundException e) {
                    log("Google multi dex installs error . Try inject classLoader ." + Log.getStackTraceString(e));
                    installSecondaryDexesByInjectClassLoader(loader, dexDir, files);
                }
            }
            installedApk.add(sourceApk);
        }
    }

    private static ApplicationInfo getApplicationInfo(Context context) {
        try {
            /* Due to package install races it is possible for a process to be started from an old
             * apk even though that apk has been replaced. Querying for ApplicationInfo by package
             * name may return information for the new apk, leading to a runtime with the old main
             * dex file and new secondary dex files. This leads to various problems like
             * ClassNotFoundExceptions. Using context.getApplicationInfo() should result in the
             * process having a consistent view of the world (even if it is of the old world). The
             * package install races are eventually resolved and old processes are killed.
             */
            return context.getApplicationInfo();
        } catch (RuntimeException e) {
            /* Ignore those exceptions so that we don't break tests relying on Context like
             * a android.test.mock.MockContext or a android.content.ContextWrapper with a null
             * base Context.
             */
            log("Failure while trying to obtain ApplicationInfo from Context. " +
                    "Must be running in test mode. Skip patching.", e);
            return null;
        }
    }

    /**
     * Identifies if the current VM has a native support for multidex, meaning there is no need for
     * additional installation by this library.
     *
     * @return true if the VM handles multidex
     */
    /* package visible for test */
    static boolean isVMMultidexCapable(String versionString) {
        boolean isMultidexCapable = false;
        if (versionString != null) {
            Matcher matcher = Pattern.compile("(\\d+)\\.(\\d+)(\\.\\d+)?")
                                     .matcher(versionString);
            if (matcher.matches()) {
                try {
                    int major = Integer.parseInt(matcher.group(1));
                    int minor = Integer.parseInt(matcher.group(2));
                    isMultidexCapable = (major > VM_WITH_MULTIDEX_VERSION_MAJOR)
                            || ((major == VM_WITH_MULTIDEX_VERSION_MAJOR)
                            && (minor >= VM_WITH_MULTIDEX_VERSION_MINOR));
                } catch (NumberFormatException e) {
                    // let isMultidexCapable be false
                }
            }
        }
        log("VM with version " + versionString +
                (isMultidexCapable ?
                        " has multidex support" :
                        " does not have multidex support"));
        return isMultidexCapable;
    }

    public static void installSecondaryDexesByInjectClassLoader(ClassLoader loader, File dexDir, List<? extends File> files)
            throws NoSuchFieldException, IllegalAccessException, NoSuchMethodException, InvocationTargetException {
        ArrayList<String> filePaths = new ArrayList<>(files.size());
        for (File zipFile : files) {
            filePaths.add(zipFile.getAbsolutePath());
        }
        String nativeLibraryPath = "";
        try {
            nativeLibraryPath = (String) loader.getClass()
                                               .getMethod("getLdLibraryPath", new Class[0])
                                               .invoke(loader, new Object[0]);
        } catch (Exception e) {
            log("Failed to determine native library path " + e);
        }
        if (TextUtils.isEmpty(nativeLibraryPath) && Build.VERSION.SDK_INT <= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            nativeLibraryPath = getLdLibraryPath(loader);
            log("Default getLdLibraryPath from ClassLoader return null , try custom getLdLibraryPath return" + nativeLibraryPath);
        }
        restore(loader);
        IncrementalClassLoader.inject(loader, nativeLibraryPath, dexDir.getAbsolutePath(), filePaths);
    }

    public static void installSecondaryDexes(ClassLoader loader, File dexDir, List<? extends File> files)
            throws IllegalArgumentException, IllegalAccessException, NoSuchFieldException,
            InvocationTargetException, NoSuchMethodException, IOException {
        if (!files.isEmpty()) {
            int key = loader.hashCode();
            if (originalDexElements.get(key) != null) {
                restore(loader);
            } else {
                originalDexElements.put(key, getFieldValue(getFieldValue(loader, FIELD_NAME_PATH_LIST), "dexElements"));
            }
            long start = System.currentTimeMillis();
            if (Build.VERSION.SDK_INT >= 23) {
                V23.install(loader, files, dexDir);
            } else if (Build.VERSION.SDK_INT >= 19) {
                V19.install(loader, files, dexDir);
            } else if (Build.VERSION.SDK_INT >= 14) {
                V14.install(loader, files, dexDir);
            } else {
                V4.install(loader, files);
            }
            log("Install dex file and dex opt success time : " + (System.currentTimeMillis() - start) + "ms, file :" + files);
        } else {
            log("Install dex files were empty");
        }
    }


    /**
     * Locates a given field anywhere in the class inheritance hierarchy.
     *
     * @param instance an object to search the field into.
     * @param name     field name
     * @return a field object
     * @throws NoSuchFieldException if the field cannot be located
     */
    private static Field findField(Object instance, String name) throws NoSuchFieldException {
        for (Class<?> clazz = instance.getClass(); clazz != null; clazz = clazz.getSuperclass()) {
            try {
                Field field = clazz.getDeclaredField(name);

                if (!field.isAccessible()) {
                    field.setAccessible(true);
                }

                return field;
            } catch (NoSuchFieldException e) {
                // ignore and search next
            }
        }

        throw new NoSuchFieldException("Field " + name + " not found in " + instance.getClass());
    }

    /**
     * Get value of given field anywhere in the class inheritance hierarchy.
     *
     * @param instance an object to search the field into.
     * @param name     field name
     * @return a object
     * @throws NoSuchFieldException   if the field cannot be located
     * @throws IllegalAccessException if the field cannot be accessed
     */
    private static Object getFieldValue(Object instance, String name) throws NoSuchFieldException, IllegalAccessException {
        return findField(instance, name).get(instance);
    }

    /**
     * Locates a given method anywhere in the class inheritance hierarchy.
     *
     * @param instance       an object to search the method into.
     * @param name           method name
     * @param parameterTypes method parameter types
     * @return a method object
     * @throws NoSuchMethodException if the method cannot be located
     */
    private static Method findMethod(Object instance, String name, Class<?>... parameterTypes)
            throws NoSuchMethodException {
        for (Class<?> clazz = instance.getClass(); clazz != null; clazz = clazz.getSuperclass()) {
            try {
                Method method = clazz.getDeclaredMethod(name, parameterTypes);


                if (!method.isAccessible()) {
                    method.setAccessible(true);
                }

                return method;
            } catch (NoSuchMethodException e) {
                // ignore and search next
            }
        }

        throw new NoSuchMethodException("Method " + name + " with parameters " +
                Arrays.asList(parameterTypes) + " not found in " + instance.getClass());
    }

    /**
     * Replace the value of a field containing a non null array, by a new array containing the
     * elements of the original array plus the elements of extraElements.
     *
     * @param instance      the instance whose field is to be modified.
     * @param fieldName     the field to modify.
     * @param extraElements elements to append at the end of the array.
     */
    private static void expandFieldArray(Object instance, String fieldName,
                                         Object[] extraElements) throws NoSuchFieldException, IllegalArgumentException,
            IllegalAccessException {
        Field jlrField = findField(instance, fieldName);
        Object[] original = (Object[]) jlrField.get(instance);
        Object[] combined = (Object[]) Array.newInstance(
                original.getClass()
                        .getComponentType(), original.length + extraElements.length);
        System.arraycopy(original, 0, combined, 0, original.length);
        System.arraycopy(extraElements, 0, combined, original.length, extraElements.length);
        jlrField.set(instance, combined);
    }

    private static void clearOldDexDir(Context context) throws Exception {
        File dexDir = new File(context.getFilesDir(), OLD_SECONDARY_FOLDER_NAME);
        if (dexDir.isDirectory()) {
            log("Clearing old secondary dex dir (" + dexDir.getPath() + ").");
            File[] files = dexDir.listFiles();
            if (files == null) {
                log("Failed to list secondary dex dir content (" + dexDir.getPath() + ").");
                return;
            }
            for (File oldFile : files) {
                log("Trying to delete old file " + oldFile.getPath() + " of size "
                        + oldFile.length());
                if (!oldFile.delete()) {
                    log("Failed to delete old file " + oldFile.getPath());
                } else {
                    log("Deleted old file " + oldFile.getPath());
                }
            }
            if (!dexDir.delete()) {
                log("Failed to delete secondary dex dir " + dexDir.getPath());
            } else {
                log("Deleted old secondary dex dir " + dexDir.getPath());
            }
        }
    }

    public static File getDexDir(Context context, File dataDir, String secondaryFolderName)
            throws IOException {
        File cache = new File(dataDir, CODE_CACHE_NAME);
        try {
            mkdirChecked(cache);
        } catch (IOException e) {
            /* If we can't emulate code_cache, then store to filesDir. This means abandoning useless
             * files on disk if the device ever updates to android 5+. But since this seems to
             * happen only on some devices running android 2, this should cause no pollution.
             */
            log("getDexDir try to mkdir checked error ", e);
            cache = new File(context.getFilesDir(), CODE_CACHE_NAME);
            mkdirChecked(cache);
        }
        File dexDir = new File(cache, secondaryFolderName);
        mkdirChecked(dexDir);
        return dexDir;
    }

    private static void mkdirChecked(File dir) throws IOException {
        if (!dir.exists()) {
            dir.mkdir();
        }
        if (!dir.isDirectory()) {
            File parent = dir.getParentFile();
            if (parent == null) {
                log("Failed to create dir " + dir.getPath() + ". Parent file is null.");
            } else {
                log("Failed to create dir " + dir.getPath() +
                        ". parent file is a dir " + parent.isDirectory() +
                        ", a file " + parent.isFile() +
                        ", exists " + parent.exists() +
                        ", readable " + parent.canRead() +
                        ", writable " + parent.canWrite());
            }
            throw new IOException("Failed to create directory " + dir.getPath());
        }

        //check write permission
        if (useLock) {
            File tempFile = new File(dir, "temp");
            try {
                FileWriter fw = new FileWriter(tempFile);
                fw.write("1");
                fw.close();
            } finally {
                tempFile.delete();
            }
        }

    }


    /**
     * Installer for platform versions 23.
     */
    private static final class V23 {

        private static void install(ClassLoader loader, List<? extends File> additionalClassPathEntries,
                                    File optimizedDirectory)
                throws IllegalArgumentException, IllegalAccessException,
                NoSuchFieldException, InvocationTargetException, NoSuchMethodException, IOException {
            /* The patched class loader is expected to be a descendant of
             * dalvik.system.BaseDexClassLoader. We modify its
             * dalvik.system.DexPathList pathList field to append additional DEX
             * file entries.
             */
            Field pathListField = findField(loader, FIELD_NAME_PATH_LIST);
            Object dexPathList = pathListField.get(loader);
            ArrayList<IOException> suppressedExceptions = new ArrayList<IOException>();
            expandFieldArray(dexPathList, "dexElements", makePathElements(dexPathList,
                    new ArrayList<File>(additionalClassPathEntries), optimizedDirectory,
                    suppressedExceptions));
            if (suppressedExceptions.size() > 0) {
                for (IOException e : suppressedExceptions) {
                    log("Exception in makePathElement", e);
                    throw e;
                }
            }
        }

        /**
         * A wrapper around
         * {@code private static final dalvik.system.DexPathList#makePathElements}.
         */
        private static Object[] makePathElements(
                Object dexPathList, ArrayList<? extends File> files, File optimizedDirectory,
                ArrayList<IOException> suppressedExceptions)
                throws IllegalAccessException, InvocationTargetException, NoSuchMethodException {

            Method makePathElements;
            try {
                makePathElements = findMethod(dexPathList, "makePathElements", List.class, File.class,
                        List.class);
            } catch (NoSuchMethodException e) {
                log("NoSuchMethodException: makePathElements(List,File,List) failure");
                try {
                    makePathElements = findMethod(dexPathList, "makePathElements", ArrayList.class, File.class, ArrayList.class);
                } catch (NoSuchMethodException e1) {
                    log("NoSuchMethodException: makeDexElements(ArrayList,File,ArrayList) failure");
                    try {
                        log("NoSuchMethodException: try use v19 instead");
                        return V19.makeDexElements(dexPathList, files, optimizedDirectory, suppressedExceptions);
                    } catch (NoSuchMethodException e2) {
                        log("NoSuchMethodException: makeDexElements(List,File,List) failure");
                        throw e2;
                    }
                }
            }

            return (Object[]) makePathElements.invoke(dexPathList, files, optimizedDirectory, suppressedExceptions);
        }
    }

    /**
     * Installer for platform versions 19.
     */
    private static final class V19 {

        private static void install(ClassLoader loader,
                                    List<? extends File> additionalClassPathEntries,
                                    File optimizedDirectory)
                throws IllegalArgumentException, IllegalAccessException,
                NoSuchFieldException, InvocationTargetException, NoSuchMethodException {
            /* The patched class loader is expected to be a descendant of
             * dalvik.system.BaseDexClassLoader. We modify its
             * dalvik.system.DexPathList pathList field to append additional DEX
             * file entries.
             */
            Field pathListField = findField(loader, FIELD_NAME_PATH_LIST);
            Object dexPathList = pathListField.get(loader);
            ArrayList<IOException> suppressedExceptions = new ArrayList<IOException>();
            expandFieldArray(dexPathList, "dexElements", makeDexElements(dexPathList,
                    new ArrayList<File>(additionalClassPathEntries), optimizedDirectory,
                    suppressedExceptions));
            if (suppressedExceptions.size() > 0) {
                RuntimeException exception = null;
                for (IOException e : suppressedExceptions) {
                    log("Exception in makeDexElement \n", e);
                    //normally only one exception
                    exception = new RuntimeException("V19.install error", e);
                }
                Field suppressedExceptionsField =
                        findField(dexPathList, "dexElementsSuppressedExceptions");
                IOException[] dexElementsSuppressedExceptions =
                        (IOException[]) suppressedExceptionsField.get(dexPathList);

                if (dexElementsSuppressedExceptions == null) {
                    dexElementsSuppressedExceptions =
                            suppressedExceptions.toArray(
                                    new IOException[suppressedExceptions.size()]);
                } else {
                    IOException[] combined =
                            new IOException[suppressedExceptions.size() +
                                    dexElementsSuppressedExceptions.length];
                    suppressedExceptions.toArray(combined);
                    System.arraycopy(dexElementsSuppressedExceptions, 0, combined,
                            suppressedExceptions.size(), dexElementsSuppressedExceptions.length);
                    dexElementsSuppressedExceptions = combined;
                }

                suppressedExceptionsField.set(dexPathList, dexElementsSuppressedExceptions);
                if (exception != null) {
                    throw exception;
                }
            }
        }

        /**
         * A wrapper around
         * {@code private static final dalvik.system.DexPathList#makeDexElements}.
         */
        private static Object[] makeDexElements(
                Object dexPathList, ArrayList<? extends File> files, File optimizedDirectory,
                ArrayList<IOException> suppressedExceptions)
                throws IllegalAccessException, InvocationTargetException,
                NoSuchMethodException {
            Method makeDexElements = null;
            try {
                makeDexElements = findMethod(dexPathList, "makeDexElements", ArrayList.class, File.class,
                        ArrayList.class);
            } catch (NoSuchMethodException e) {
                log("makeDexElements(ArrayList,File,ArrayList) not found in " + dexPathList, e);
                makeDexElements = findMethod(dexPathList, "makeDexElements", List.class, File.class, List.class);
            }

            return (Object[]) makeDexElements.invoke(dexPathList, files, optimizedDirectory,
                    suppressedExceptions);
        }
    }

    /**
     * Installer for platform versions 14, 15, 16, 17 and 18.
     */
    private static final class V14 {

        private static void install(ClassLoader loader,
                                    List<? extends File> additionalClassPathEntries,
                                    File optimizedDirectory)
                throws IllegalArgumentException, IllegalAccessException,
                NoSuchFieldException, InvocationTargetException, NoSuchMethodException {
            /* The patched class loader is expected to be a descendant of
             * dalvik.system.BaseDexClassLoader. We modify its
             * dalvik.system.DexPathList pathList field to append additional DEX
             * file entries.
             */
            Field pathListField = findField(loader, FIELD_NAME_PATH_LIST);
            Object dexPathList = pathListField.get(loader);
            expandFieldArray(dexPathList, "dexElements", makeDexElements(dexPathList,
                    new ArrayList<File>(additionalClassPathEntries), optimizedDirectory));
        }

        /**
         * A wrapper around
         * {@code private static final dalvik.system.DexPathList#makeDexElements}.
         */
        private static Object[] makeDexElements(
                Object dexPathList, ArrayList<File> files, File optimizedDirectory)
                throws IllegalAccessException, InvocationTargetException,
                NoSuchMethodException {
            Method makeDexElements = findMethod(dexPathList, "makeDexElements", ArrayList.class, File.class);
            return (Object[]) makeDexElements.invoke(dexPathList, files, optimizedDirectory);
        }
    }

    /**
     * Installer for platform versions 4 to 13.
     */
    private static final class V4 {
        private static void install(ClassLoader loader,
                                    List<? extends File> additionalClassPathEntries)
                throws IllegalArgumentException, IllegalAccessException,
                NoSuchFieldException, IOException {
            /* The patched class loader is expected to be a descendant of
             * dalvik.system.DexClassLoader. We modify its
             * fields mPaths, mFiles, mZips and mDexs to append additional DEX
             * file entries.
             */
            int extraSize = additionalClassPathEntries.size();

            Field pathField = findField(loader, "path");

            StringBuilder path = new StringBuilder((String) pathField.get(loader));
            String[] extraPaths = new String[extraSize];
            File[] extraFiles = new File[extraSize];
            ZipFile[] extraZips = new ZipFile[extraSize];
            DexFile[] extraDexs = new DexFile[extraSize];
            for (ListIterator<? extends File> iterator = additionalClassPathEntries.listIterator();
                 iterator.hasNext(); ) {
                File additionalEntry = iterator.next();
                String entryPath = additionalEntry.getAbsolutePath();
                path.append(':')
                    .append(entryPath);
                int index = iterator.previousIndex();
                extraPaths[index] = entryPath;
                extraFiles[index] = additionalEntry;
                extraZips[index] = new ZipFile(additionalEntry);
                extraDexs[index] = DexFile.loadDex(entryPath, entryPath + ".dex", 0);
            }

            pathField.set(loader, path.toString());
            expandFieldArray(loader, "mPaths", extraPaths);
            expandFieldArray(loader, "mFiles", extraFiles);
            expandFieldArray(loader, "mZips", extraZips);
            expandFieldArray(loader, "mDexs", extraDexs);
        }
    }


    public static void registerLogger(Logger logger_) {
        logger = logger_;
    }

    public static void log(String msg) {
        if (logger != null) {
            logger.log(msg);
        } else {
            Log.i(TAG, msg);
        }
    }

    public static void log(String msg, Throwable throwable) {
        if (logger != null) {
            logger.log(msg + Log.getStackTraceString(throwable));
        } else {
            Log.e(TAG, msg, throwable);
        }
    }

    public static String getLdLibraryPath(ClassLoader loader) throws NoSuchFieldException, IllegalAccessException {
        Object pathList = getFieldValue(loader, MultiDex.FIELD_NAME_PATH_LIST);
        Object nativeLibraryDirectories = getFieldValue(pathList, "nativeLibraryDirectories");
        StringBuilder result = new StringBuilder();
        if (nativeLibraryDirectories instanceof File[]) {
            for (File directory : (File[]) nativeLibraryDirectories) {
                result.append(directory)
                      .append(':');
            }
        } else if (nativeLibraryDirectories instanceof Collection) {
            for (Object directory : (Collection) nativeLibraryDirectories) {
                result.append(directory)
                      .append(':');
            }
        }
        return result.toString();
    }
}
