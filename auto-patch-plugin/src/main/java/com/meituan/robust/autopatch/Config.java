package com.meituan.robust.autopatch;

import com.meituan.robust.Constants;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javassist.ClassPool;
import javassist.CtMethod;

import static com.meituan.robust.Constants.DEFAULT_MAPPING_FILE;

/**
 * Created by mivanzhang on 16/12/2.
 * <p>
 * members read from robust.xml
 */

public final class Config {
    public static boolean catchReflectException = false;
    public static boolean supportProGuard = true;
    public static boolean isLogging = true;
    public static boolean isManual = false;
    // 是否改变补丁的输出目录
    public static boolean changePatchDir = false;
    // 补丁的输出目录，项目根目录是补丁输出目录的父目录或者根目录，例如目录是projectRootDir/patch，则该值为patch
    public static String patchTargetDir = null;
    // 构建类型
    public static String patchBuildType = "release";
    // 是否删除多余的生成文件，若为true，则把补丁包移动到project.buildDir中，并删除其他文件夹
    public static boolean deleteOutputs = false;

    public static String patchPackageName = Constants.PATCH_PACKAGENAME;
    public static String mappingFilePath;
    public static Set<String> patchMethodSignatureSet = new HashSet<>();
    public static List<String> newlyAddedClassNameList = new ArrayList<>();
    public static Set newlyAddedMethodSet = new HashSet<String>();
    public static List<String> modifiedClassNameList = new ArrayList<>();
    public static List<String> hotfixPackageList = new ArrayList<>();
    public static LinkedHashMap<String, Integer> methodMap = new LinkedHashMap<>();
    public static String robustGenerateDirectory;
    public static Map<String, List<CtMethod>> invokeSuperMethodMap = new HashMap<>();
    public static ClassPool classPool = new ClassPool();
    public static Set methodNeedPatchSet = new HashSet();
    public static List<CtMethod> addedSuperMethodList = new ArrayList<>();
    public static Set<String> noNeedReflectClassSet = new HashSet<>();

    public static void init() {
        catchReflectException = false;
        isLogging = true;
        isManual = false;
        patchPackageName = Constants.PATCH_PACKAGENAME;
        mappingFilePath = DEFAULT_MAPPING_FILE;
        patchMethodSignatureSet = new HashSet<>();
        newlyAddedClassNameList = new ArrayList<>();
        modifiedClassNameList = new ArrayList<>();
        hotfixPackageList = new ArrayList<>();
        newlyAddedMethodSet = new HashSet<>();
        invokeSuperMethodMap = new HashMap<>();
        classPool = new ClassPool();
        methodNeedPatchSet = new HashSet();
        addedSuperMethodList = new ArrayList<>();
        noNeedReflectClassSet = new HashSet<>();
        noNeedReflectClassSet.addAll(Constants.NO_NEED_REFLECT_CLASS);
        supportProGuard = true;
    }
}
