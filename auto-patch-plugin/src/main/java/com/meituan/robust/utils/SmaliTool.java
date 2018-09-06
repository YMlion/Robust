package com.meituan.robust.utils;

import com.meituan.robust.Constants;
import com.meituan.robust.autopatch.ClassMapping;
import com.meituan.robust.autopatch.Config;
import com.meituan.robust.autopatch.NameManger;
import com.meituan.robust.autopatch.ReadMapping;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import javassist.CtClass;
import javassist.CtMethod;
import javassist.CtPrimitiveType;

import static com.meituan.robust.Constants.PACKNAME_END;
import static com.meituan.robust.Constants.PACKNAME_START;
import static com.meituan.robust.autopatch.Config.classPool;
import static com.meituan.robust.autopatch.Config.invokeSuperMethodMap;

/**
 * Created by mivanzhang on 17/2/8.
 */

public class SmaliTool {
    private static SmaliTool instance;

    public static SmaliTool getInstance() {
        if (instance == null) {
            instance = new SmaliTool();
        }
        return instance;
    }

    private SmaliTool() {

    }

    public void dealObscureInSmali() {
        File directory = new File(Config.robustGenerateDirectory
                                          + "classout"
                                          + File.separator
                                          + Config.patchPackageName
                .replaceAll("\\.", Matcher.quoteReplacement(File.separator)));
        if (!directory.isDirectory()) {
            throw new RuntimeException(Config.robustGenerateDirectory
                                               + Config.patchPackageName
                    .replaceAll(".", Matcher.quoteReplacement(File.separator))
                                               + " contains no smali file error!! ");
        }
        List<File> smaliFileList =
                covertPathToFile(Config.robustGenerateDirectory + "classout" + File.separator,
                                 Config.newlyAddedClassNameList);
        smaliFileList.addAll(Arrays.asList(Objects.requireNonNull(directory.listFiles())));
        for (File file : smaliFileList) {
            BufferedWriter writer = null;
            BufferedReader reader = null;
            StringBuilder fileContent = new StringBuilder();
            try {
                reader = new BufferedReader(new FileReader(file));
                String line;
                int lineNo = 1;
                // 一次读入一行，直到读入null为文件结束
                while ((line = reader.readLine()) != null) {
                    // 显示行号
                    fileContent.append(dealWithSmaliLine(line, JavaUtils
                            .getFullClassNameFromFile(file.getPath()))).append("\n");
                    lineNo++;
                }
                writer = new BufferedWriter(new FileWriter(file));
                writer.write(fileContent.toString());
                writer.flush();
            } catch (IOException e) {
                e.printStackTrace();
            } finally {
                if (reader != null) {
                    try {
                        reader.close();
                    } catch (IOException e1) {
                        e1.printStackTrace();
                    }
                }
                if (writer != null) {
                    try {
                        writer.close();
                    } catch (IOException e1) {
                        e1.printStackTrace();
                    }
                }
            }
        }
    }

    private List<File> covertPathToFile(String directory, List<String> packNameList) {
        if (packNameList == null) {
            return new ArrayList<>();
        }
        List<File> fileList = new ArrayList<>();
        for (String packname : packNameList) {
            fileList.add(new File(directory
                                          + packname
                    .replaceAll("\\.", Matcher.quoteReplacement(File.separator))
                                          + ".smali"));
        }
        return fileList;
    }

    private String dealWithSmaliLine(final String line, String fullClassName) {

        if (null == line || line.length() < 1 || line.startsWith("#")) {
            return line;
        }

        // 主要针对 @Add 的新增类，需要为其创建混淆规则，为以后混淆方法名做准备。
        if (Config.newlyAddedClassNameList.contains(fullClassName)) {
            if (ReadMapping.getInstance().getClassMapping(fullClassName) == null) {
                ClassMapping classMapping = new ClassMapping();
                classMapping.setClassName(fullClassName);
                classMapping.setValueName(fullClassName);
                ReadMapping.getInstance().setClassMapping(fullClassName, classMapping);
            }

            if (line.startsWith(".super") || line.startsWith(".implements")) {
                List<String> packNameFromSmaliLine = getPackNameFromSmaliLine(line);
                if (packNameFromSmaliLine.size() > 0) {
                    String className = packNameFromSmaliLine.get(0).replaceAll("/", "\\.");
                    ClassMapping superClassMapping =
                            ReadMapping.getInstance().getClassMapping(className);
                    ClassMapping newClassMapping =
                            ReadMapping.getInstance().getClassMapping(fullClassName);
                    if (superClassMapping != null) {
                        for (String key : superClassMapping.getMemberMapping().keySet()) {
                            // 理论上.super的出现的比.implement出现早，而且继承的混淆规则应该更准确，所以在这里谁先进map谁优先。
                            if (!newClassMapping.getMemberMapping().containsKey(key)) {
                                newClassMapping.getMemberMapping().put(key, superClassMapping
                                        .getMemberMapping().get(key));
                            }
                        }
                    }
                }
            }
        }

        String result = invokeSuperMethodInSmali(line, fullClassName);

        int packageNameIndex;
        int previousPackageNameIndex = 0;
        List<String> packageNameList = getPackNameFromSmaliLine(result);
        packageNameList.sort((o1, o2) -> o2.length() - o1.length());

        for (int index = 0; index < packageNameList.size(); index++) {

            if (result.indexOf(packageNameList.get(index)) != result
                    .lastIndexOf(packageNameList.get(index))) {
                packageNameIndex =
                        result.indexOf(packageNameList.get(index), previousPackageNameIndex);
                previousPackageNameIndex = packageNameIndex + packageNameList.get(index).length();
            } else {
                packageNameIndex = result.indexOf(packageNameList.get(index));
            }

            //invoke-virtual {v0, v5, v6, p0}, Landroid/support/v4/app/LoaderManager;->initLoader(ILandroid/os/Bundle;Landroid/support/v4/app/bi;)Landroid/support/v4/content/Loader;
            if (result.contains("invoke") && (packageNameIndex
                    + packageNameList.get(index).length()
                    + 3 < result.length()) && result
                    .substring(packageNameIndex + packageNameList.get(index).length() + 1,
                               packageNameIndex + packageNameList.get(index).length() + 3)
                    .equals("->")) {
                //方法调用的替换
                result = result.replace(
                        result.substring(packageNameIndex + packageNameList.get(index).length() + 3,
                                         result.indexOf(")") + 1), getObscuredMethodSignature(
                                result.substring(
                                        packageNameIndex + packageNameList.get(index).length() + 3),
                                packageNameList.get(index).replaceAll("/", "\\.")));
            } else if (result.contains("->") && (!result.contains("(")) && ((packageNameIndex
                    + packageNameList.get(index).length()
                    + 3) < result.length())) {
                // 字段处理
                //sget-object v4, Lcom/sankuai/meituan/fingerprint/FingerprintConfig;->accelerometerInfoList:Ljava/util/List;
                String fieldName =
                        result.substring(packageNameIndex + packageNameList.get(index).length() + 3,
                                         result.lastIndexOf(":"));
                // 前后都加上 "->" 是为了避免类名中包含字段名时，类名被误修改导致patch生成错误
                result = result.replaceFirst("->" + fieldName, "->" + getObscuredMemberName(
                        packageNameList.get(index).replaceAll("/", "\\."), fieldName));
            }
        }

        // 处理@Add新增类的方法名混淆
        if (Config.newlyAddedClassNameList.contains(fullClassName)) {
            if (result.startsWith(".method ") && !result.contains("constructor <init>") && !result
                    .contains("constructor <clinit>")) {
                System.out.println("new Add class: line = " + line);
                //.method public onFailure(Lcom/bytedance/retrofit2/Call;Ljava/lang/Throwable;)V
                int start = result.indexOf("(");
                for (; start >= 0; start--) {
                    if (line.charAt(start) == ' ') {
                        break;
                    }
                }
                String methodSignature = result.substring(start + 1);
                // 注意getObscuredMethodSignure并没有混淆返回值，不能在下面这句话之后直接返回。还需要最后混淆一次
                result = result.replace(
                        methodSignature.substring(0, methodSignature.indexOf(")") + 1),
                        getObscuredMethodSignature(methodSignature, fullClassName));
            }
        }

        for (int index = 0; index < packageNameList.size(); index++) {
            result = result.replace(packageNameList.get(index),
                                    getObscuredClassName(packageNameList.get(index)));
        }
        return result;
    }

    private boolean isInStaticRobustMethod = false;

    private String invokeSuperMethodInSmali(final String line, String fullClassName) {

        if (line.startsWith(".method public static staticRobust")) {
            isInStaticRobustMethod = true;
        }
        String result = line;
        String returnType;
        List<CtMethod> invokeSuperMethodList = invokeSuperMethodMap
                .get(NameManger.getInstance().getPatchNameMap().get(fullClassName));
        if (isInStaticRobustMethod && line.contains(Constants.SMALI_INVOKE_VIRTUAL_COMMAND)) {
            for (CtMethod ctMethod : invokeSuperMethodList) {
                //java method signure
                if ((ctMethod.getName().replaceAll("\\.", "/") + ctMethod.getSignature()
                                                                         .subSequence(0,
                                                                                      ctMethod.getSignature()
                                                                                              .indexOf(
                                                                                                      ")")
                                                                                              + 1))
                        .equals(getMethodSignatureInSmaliLine(line))) {
                    result = line.replace(Constants.SMALI_INVOKE_VIRTUAL_COMMAND,
                                          Constants.SMALI_INVOKE_SUPER_COMMAND);
                    try {
                        if (!ctMethod.getReturnType().isPrimitive()) {
                            returnType =
                                    "L" + ctMethod.getReturnType().getName().replaceAll("\\.", "/");
                        } else {
                            returnType = String.valueOf(
                                    ((CtPrimitiveType) ctMethod.getReturnType()).getDescriptor());
                        }
                        if (NameManger.getInstance().getPatchNameMap().get(fullClassName)
                                      .equals(fullClassName)) {
                            result = result.replace("p0", "p1");
                        }
                        String fullClassNameInSmali =
                                ctMethod.getDeclaringClass().getClassPool().get(fullClassName)
                                        .getSuperclass().getName().replaceAll("\\.", "/");
                        result = result.replace(result.substring(result.indexOf(PACKNAME_START) + 1,
                                                                 result.indexOf(PACKNAME_END)),
                                                fullClassNameInSmali);
                        result = result.substring(0, result.indexOf(")") + 1) + returnType;
                        if (!ctMethod.getReturnType().isPrimitive()) {
                            result += ";";
                        }
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }
            }
        }
        if (isInStaticRobustMethod && line.startsWith(".end method")) {
            isInStaticRobustMethod = false;
        }
        //        System.out.println("  result is    " + result);
        return result;
    }

    private String getMethodSignatureInSmaliLine(String s) {
        return s.substring(s.indexOf("->") + 2, s.indexOf(")") + 1);
    }

    private List<String> getPackNameFromSmaliLine(String line) {
        ArrayList<String> packageNameList = new ArrayList<>();
        if (null == line) {
            return packageNameList;
        }
        int startIndex;
        int endIndex;
        for (; line.length() > 0; ) {
            startIndex = 0;
            do {
                startIndex = line.indexOf(Constants.PACKNAME_START, startIndex + 1);
            } while (startIndex >= 0
                    && Character.isLetter(line.charAt(startIndex - 1))
                    && line.lastIndexOf(Constants.PACKNAME_START) != startIndex);
            endIndex = line.indexOf(Constants.PACKNAME_END, startIndex);
            if (startIndex < 0 || endIndex < 0) {
                break;
            }
            packageNameList.add(line.substring(startIndex + 1, endIndex));
            line = line.substring(endIndex);
        }

        //        if (packageNameList.size() > 0)
        //            System.out.println("getPackNameFromSmaliLine  " + packageNameList);
        return packageNameList;
    }

    public static void main(String[] args) {
        SmaliTool smaliUitils = new SmaliTool();
        smaliUitils.getObscuredMethodSignature(
                "invokeReflectConstruct(Ljava/lang/String;[Ljava/lang/Object;[Ljava/lang/Class;)Ljava/lang/Object;",
                "com.meituan.second");
    }

    private String getObscuredMethodSignature(final String line, String className) {

        if (className.endsWith(Constants.PATCH_SUFFIX) && Config.modifiedClassNameList
                .contains(className.substring(0, className.indexOf(Constants.PATCH_SUFFIX)))) {
            className = className.substring(0, className.indexOf(Constants.PATCH_SUFFIX));
        }
        StringBuilder methodSignatureBuilder = new StringBuilder();
        methodSignatureBuilder.append(line, 0, line.indexOf("(") + 1);
        String parameter = line.substring(line.indexOf("("), line.indexOf(")") + 1);
        int endIndex = line.indexOf(")");
        String methodSignature = line.substring(0, endIndex + 1);
        //invokeReflectConstruct(Ljava/lang/String;[Ljava/lang/Object;[Ljava/lang/Class;)Ljava/lang/Object;
        boolean isArray = false;
        for (int index = line.indexOf("(") + 1; index < endIndex; index++) {
            if (Constants.PACKNAME_START.equals(String.valueOf(methodSignature.charAt(index)))
                    && methodSignature.contains(Constants.PACKNAME_END)) {
                methodSignatureBuilder.append(methodSignature.substring(index + 1, methodSignature
                        .indexOf(Constants.PACKNAME_END, index)).replaceAll("/", "\\."));
                if (isArray) {
                    methodSignatureBuilder.append("[]");
                    isArray = false;
                }
                index = methodSignature.indexOf(";", index);
                methodSignatureBuilder.append(",");
            }
            if (Constants.PRIMITIVE_TYPE.contains(String.valueOf(methodSignature.charAt(index)))) {

                switch (methodSignature.charAt(index)) {
                    case 'Z':
                        methodSignatureBuilder.append("boolean");
                        break;
                    case 'C':
                        methodSignatureBuilder.append("char");
                        break;
                    case 'B':
                        methodSignatureBuilder.append("byte");
                        break;
                    case 'S':
                        methodSignatureBuilder.append("short");
                        break;
                    case 'I':
                        methodSignatureBuilder.append("int");
                        break;
                    case 'J':
                        methodSignatureBuilder.append("long");
                        break;
                    case 'F':
                        methodSignatureBuilder.append("float");
                        break;
                    case 'D':
                        methodSignatureBuilder.append("double");
                        break;
                    case 'V':
                        methodSignatureBuilder.append("void");
                        break;
                    default:
                        break;
                }
                if (isArray) {
                    methodSignatureBuilder.append("[]");
                    isArray = false;
                }
                methodSignatureBuilder.append(",");
            }

            if (Constants.ARRAY_TYPE.equals(String.valueOf(methodSignature.charAt(index)))) {
                isArray = true;
            }
        }

        List<String> returnTypeList = gePackageNameFromSmaliLine(line.substring(endIndex + 1));
        if (String.valueOf(
                methodSignatureBuilder.charAt(methodSignatureBuilder.toString().length() - 1))
                  .equals(",")) {
            methodSignatureBuilder.deleteCharAt(methodSignatureBuilder.toString().length() - 1);
        }
        methodSignatureBuilder.append(")");
        String obscuredMethodSignature = methodSignatureBuilder.toString();
        String obscuredMethodName = getObscuredMemberName(className, ReadMapping.getInstance()
                                                                                .getMethodSignatureWithReturnType(
                                                                                        returnTypeList
                                                                                                .get(0),
                                                                                        obscuredMethodSignature));
        obscuredMethodSignature = obscuredMethodName + parameter;
        //        System.out.println("getObscuredMethodSignature is "+obscuredMethodSignature.substring(0, obscuredMethodSignature.indexOf("(")) + parameter);
        return obscuredMethodSignature.substring(0, obscuredMethodSignature.indexOf("("))
                + parameter;
    }

    private List<String> gePackageNameFromSmaliLine(String smaliLine) {
        List<String> packageNameList = new ArrayList<>();
        for (int index = 0; index < smaliLine.length(); index++) {
            if (Constants.PACKNAME_START.equals(String.valueOf(smaliLine.charAt(index)))
                    && smaliLine.contains(Constants.PACKNAME_END)) {
                packageNameList.add(smaliLine.substring(index + 1, smaliLine
                        .indexOf(Constants.PACKNAME_END, index)).replaceAll("/", "\\."));
                index = smaliLine.indexOf(";", index);
            }
            if (Constants.PRIMITIVE_TYPE.contains(String.valueOf(smaliLine.charAt(index)))) {

                switch (smaliLine.charAt(index)) {
                    case 'Z':
                        packageNameList.add("boolean");
                        break;
                    case 'C':
                        packageNameList.add("char");
                        break;
                    case 'B':
                        packageNameList.add("byte");
                        break;
                    case 'S':
                        packageNameList.add("short");
                        break;
                    case 'I':
                        packageNameList.add("int");
                        break;
                    case 'J':
                        packageNameList.add("long");
                        break;
                    case 'F':
                        packageNameList.add("float");
                        break;
                    case 'D':
                        packageNameList.add("double");
                        break;
                    case 'V':
                        packageNameList.add("void");
                        break;
                    default:
                        break;
                }
            }
        }
        return packageNameList;
    }

    private String getObscuredMemberName(String className, String memberName) {

        ClassMapping classMapping = ReadMapping.getInstance().getClassMapping(className);
        if (classMapping == null) {
            System.out.println("Warning: getObscuredMemberName  class  name "
                                       + className
                                       + "   member name is  "
                                       + memberName
                                       + "  robust can not find in mapping!!! ");
            return JavaUtils.eradicatReturnType(memberName);
        }

        while (classMapping != null && !"java.lang.Object".equals(classMapping.getClassName())) {
            if (classMapping.getMemberMapping().get(memberName) != null) {
                return classMapping.getMemberMapping().get(memberName);
            } else {
                try {
                    CtClass superClass = classPool.get(classMapping.getClassName()).getSuperclass();
                    while (ReadMapping.getInstance().getClassMapping(superClass.getName()) == null
                            && !"java.lang.Object".equals(superClass.getName())) {
                        superClass = superClass.getSuperclass();
                    }
                    classMapping = ReadMapping.getInstance().getClassMapping(superClass.getName());
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        }
        return JavaUtils.eradicatReturnType(memberName);
    }

    private String getObscuredClassName(String className) {
        ClassMapping classMapping =
                ReadMapping.getInstance().getClassMapping(className.replaceAll("/", "\\."));
        if (null == classMapping || classMapping.getValueName() == null) {
            return className;
        }
        return classMapping.getValueName().replaceAll("\\.", "/");
    }
}
