package com.meituan.robust.autopatch

import com.meituan.robust.Constants
import com.meituan.robust.patch.annotaion.Add
import com.meituan.robust.patch.annotaion.Modify
import com.meituan.robust.utils.JavaUtils
import javassist.*
import javassist.bytecode.Descriptor
import javassist.expr.ExprEditor
import javassist.expr.MethodCall
import org.codehaus.groovy.GroovyException
import org.gradle.api.logging.Logger
import robust.gradle.plugin.AutoPatchTransform

class ReadAnnotation {
    static Logger logger
    static int index = 0
    //    static volatile def lambda2FixList = new ArrayList<String>()

    static void readAnnotation(List<CtClass> box, Logger log) {
        logger = log
        Set patchMethodSignureSet = new HashSet<String>()
        synchronized (AutoPatchTransform.class) {
            if (Constants.ModifyAnnotationClass == null) {
                Constants.ModifyAnnotationClass =
                    box.get(0).getClassPool().get(Constants.MODIFY_ANNOTATION).toClass()
            }
            if (Constants.AddAnnotationClass == null) {
                Constants.AddAnnotationClass =
                    box.get(0).getClassPool().get(Constants.ADD_ANNOTATION).toClass()
            }
        }
        box.forEach { ctclass ->
            try {
                boolean isNewlyAddClass = scanClassForAddClassAnnotation(ctclass)
                //newly add class donnot need scann for modify
                if (!isNewlyAddClass) {
                    patchMethodSignureSet.addAll(scanClassForModifyMethod(ctclass))
                    scanClassForAddMethodAnnotation(ctclass)
                }
            } catch (NullPointerException e) {
                logger.warn("something wrong when readAnnotation, " + e.getMessage() +
                    " cannot find class name " +
                    ctclass.name)
                e.printStackTrace()
            } catch (RuntimeException e) {
                logger.warn("something wrong when readAnnotation, " + e.getMessage() +
                    " cannot find class name " +
                    ctclass.name)
                e.printStackTrace()
            }
        }
        println("new add methods  list is ")
        JavaUtils.printList(Config.newlyAddedMethodSet.toList())
        println("new add classes list is ")
        JavaUtils.printList(Config.newlyAddedClassNameList)
        println(" patchMethodSignatureSet is printed below ")
        JavaUtils.printList(patchMethodSignureSet.asList())
        Config.patchMethodSignatureSet.addAll(patchMethodSignureSet)
    }

    static boolean scanClassForAddClassAnnotation(CtClass ctclass) {

        Add addClassAnnotation = ctclass.getAnnotation(Constants.AddAnnotationClass) as Add
        if (addClassAnnotation != null && !Config.newlyAddedClassNameList.contains(ctclass.name)) {
            Config.newlyAddedClassNameList.add(ctclass.name)
            return true
        }

        return false
    }

    static void scanClassForAddMethodAnnotation(CtClass ctclass) {

        ctclass.defrost()
        ctclass.declaredMethods.each { method ->
            if (null != method.getAnnotation(Constants.AddAnnotationClass)) {
                Config.newlyAddedMethodSet.add(method.longName)
            }
        }
    }

    static Set scanClassForModifyMethod(CtClass ctclass) {
        Set patchMethodSignureSet = new HashSet<String>()
        boolean isAllMethodsPatch = true
        ctclass.declaredMethods.findAll {
            return it.hasAnnotation(Constants.ModifyAnnotationClass)
        }.each { method ->
            isAllMethodsPatch = false
            addPatchMethodAndModifiedClass(patchMethodSignureSet, method)
        }

        //do with lambda expression
        ctclass.defrost()
        ctclass.declaredMethods.findAll {
            return Config.methodMap.get(it.longName) != null
        }.each { method ->
            def isPatch = false
            def isOutMethodCall = false
            method.instrument(new ExprEditor() {
                @Override
                void edit(MethodCall m) throws CannotCompileException {
                    if (isPatch) {
                        println "this method has patched."
                        return
                    }
                    try {
                        def callMethod = m.method
                        println "find modify methods : " + method.longName +
                            " :: " +
                            callMethod.declaringClass.name +
                            "; " +
                            m.methodName
                        if (Constants.LAMBDA_MODIFY == callMethod.declaringClass.name) {
                            isPatch = true
                            isAllMethodsPatch = false
                            addPatchMethodAndModifiedClass(patchMethodSignureSet, method)
                        } else if (m.methodName.contains("lambda\$") && m.methodName.endsWith(
                            callMethod.declaringClass.simpleName)) {
                            callMethod.instrument(new ExprEditor() {
                                @Override
                                void edit(MethodCall mm) throws CannotCompileException {
                                    println "!! find modify methods : " + m.method.longName +
                                        " :: " +
                                        mm.method.declaringClass.name +
                                        "; " +
                                        mm.methodName
                                    if (Constants.LAMBDA_MODIFY == mm.method.declaringClass.name) {
                                        isPatch = true
                                    } else if (m.method.declaringClass.name == mm.method.declaringClass.name) {
                                        isOutMethodCall = true
                                    }
                                }
                            })
                            try {
                                if (isPatch) {
                                    def newMethod
                                    if (isOutMethodCall) {
                                        // 修复代码中有外部类方法调用
                                        if (hasOutParams(method, callMethod)) {
                                            println "lambda fix : 1"
                                            // 外部变量引用，此时callMethod不会在dex优化中被优化掉
                                            newAndReplaceMethod(callMethod, method, m)
                                        } else {
                                            // 无外部变量引用，此时在dex优化时会被优化掉
                                            // 此时还有两种情况，一种是原来该方法中就有调用外部类的方法，另一种就是只在修复代码中有外部类方法调用
                                            // 但是无法判断这两种情况，因为当前的代码都是修复之后的代码编译而成
                                            //  lambda2FixList.add(method.declaringClass.name)
                                            println "lambda fix : 2 "
                                            newAndReplaceMethod(callMethod, method, m)
                                        }
                                        newMethod = method
                                    } else {
                                        // 修复代码无外部类方法调用
                                        def originalClass = method.getDeclaringClass()
                                        if (hasOutParams(method, callMethod)) {
                                            println "lambda fix : 3"
                                            // 有外部变量引用
                                            // 复制并创建一个新的方法，并替换掉当前调用的方法
                                            newMethod = CtNewMethod.copy(callMethod,
                                                method.getName() + "_temp", originalClass, null)
                                            originalClass.addMethod(newMethod)
                                            m.replace("{ ${newMethod.name}(\$\$); }")
                                            newMethod = method
                                        } else {
                                            println "lambda fix : 4"
                                            // 仅是逻辑代码，不涉及外部方法和变量引用
                                            // 复制当前方法到原来的方法中，减少此次方法调用，直接复制会有问题，通过创建同名方法实现
                                            originalClass.removeMethod(method)
                                            newMethod = CtNewMethod.copy(callMethod,
                                                method.getName(), originalClass, null)
                                            originalClass.addMethod(newMethod)
                                        }
                                    }
                                    isAllMethodsPatch = false
                                    addPatchMethodAndModifiedClass(patchMethodSignureSet,
                                        newMethod)
                                }
                            } catch (Exception e) {
                                e.printStackTrace()
                            }
                        }
                    } catch (Exception e) {
                        e.printStackTrace()
                        logger.warn("  cannot find class  " + method.longName +
                            " line number " +
                            m.lineNumber +
                            " this class may never used ,please remove this class")
                    }
                }

                /*void edit(NewExpr e) throws CannotCompileException {
                    if (lambda2FixList.contains(e.className)) {
                        // 第二种情况
                        // 由于无法判断是否是修复之后才有了外部类方法调用，所以也要修复把创建lambda表达式的方法
                        println "lambda fix : 2-2"
                        lambda2FixList.remove(e.className)
                        isAllMethodsPatch = false
                        addPatchMethodAndModifiedClass(patchMethodSignureSet,
                            method)
                        Config.newlyAddedMethodSet.add(e.constructor.longName)
                    }
                }*/
            })
        }
        Modify classModifyAnnotation = ctclass.getAnnotation(
            Constants.ModifyAnnotationClass) as Modify
        if (classModifyAnnotation != null) {
            if (isAllMethodsPatch) {
                if (classModifyAnnotation.value().length() < 1) {
                    ctclass.declaredMethods.findAll {
                        return Config.methodMap.get(it.longName) != null
                    }.each { method -> addPatchMethodAndModifiedClass(patchMethodSignureSet, method)
                    }
                } else {
                    ctclass.getClassPool().
                        get(classModifyAnnotation.value()).declaredMethods.
                        findAll {
                            return Config.methodMap.get(it.longName) != null
                        }.
                        each {
                            method -> addPatchMethodAndModifiedClass(patchMethodSignureSet, method)
                        }
                }
            }
        }
        return patchMethodSignureSet
    }

    /**
     * 在外部类中生成新的方法，代替编译时生成的方法
     *
     * @param copyMethod 编译时生成的无插桩方法
     * @param innerMethod 内部类方法，该方法去调用编译时生成的外部类方法，该方法就是需要修复的方法
     * @return 在外部类中新生成的成员方法
     */
    static CtMethod generateNewOutMethod(CtMethod copyMethod, CtMethod innerMethod) {
        if (Config.methodMap.get(copyMethod.longName) != null) {
            Config.methodMap.remove(copyMethod.longName)
        }
        def newMethod = CtNewMethod.copy(copyMethod,
            innerMethod.getName() + "\$Proxy\$" + Config.methodMap.get(innerMethod.longName).
                intValue(),
            copyMethod.declaringClass, null)
        newMethod.setModifiers(Modifier.FINAL | Modifier.PUBLIC)
        copyMethod.declaringClass.addMethod(newMethod)
        return newMethod
    }

    /**
     * 在外部类中生成新的方法，代替编译时生成的方法
     *
     * @param copyMethod 编译时生成的无插桩方法
     * @param innerMethod 内部类方法，该方法去调用编译时生成的外部类方法，该方法就是需要修复的方法
     * @param mc 无插桩方法调用
     */
    static void newAndReplaceMethod(CtMethod replacedMethod, CtMethod innerMethod, MethodCall mc) {
        def newMethod = generateNewOutMethod(replacedMethod, innerMethod)
        println "return type is " + newMethod.returnType.name
        // 不能是静态的，应该使用调用者去调用
        if (newMethod.returnType.name == "void") {
            mc.replace("{ \$0.${newMethod.name}(\$\$); }")
        } else {
            mc.replace("{ \$_ = \$0.${newMethod.name}(\$\$); }")
        }
        newMethod.declaringClass.removeMethod(replacedMethod)
    }

    static boolean hasOutParams(CtMethod m1, CtMethod m2) {
        return Descriptor.numOfParameters(m1.methodInfo.descriptor) != Descriptor.
            numOfParameters(m2.methodInfo.descriptor)
    }

    static Set addPatchMethodAndModifiedClass(Set patchMethodSignureSet, CtMethod method) {
        if (Config.methodMap.get(method.longName) == null) {
            print("addPatchMethodAndModifiedClass pint methodmap ")
            JavaUtils.printMap(Config.methodMap)
            throw new GroovyException("patch method " + method.longName +
                " haven't insert code by Robust.Cannot patch this method, method.signature  " +
                method.signature +
                "  ")
        }
        Modify methodModifyAnnotation = method.getAnnotation(
            Constants.ModifyAnnotationClass) as Modify
        Modify classModifyAnnotation = method.declaringClass.getAnnotation(
            Constants.ModifyAnnotationClass) as Modify
        if ((methodModifyAnnotation == null || methodModifyAnnotation.value().length() < 1)) {
            //no annotation value
            patchMethodSignureSet.add(method.longName)
            if (!Config.modifiedClassNameList.contains(method.declaringClass.name)) {
                Config.modifiedClassNameList.add(method.declaringClass.name)
            }
        } else {
            //use value in annotation
            patchMethodSignureSet.add(methodModifyAnnotation.value())
        }
        if (classModifyAnnotation == null || classModifyAnnotation.value().length() < 1) {
            if (!Config.modifiedClassNameList.contains(method.declaringClass.name)) {
                Config.modifiedClassNameList.add(method.declaringClass.name)
            }
        } else {
            if (!Config.modifiedClassNameList.contains(classModifyAnnotation.value())) {
                Config.modifiedClassNameList.add(classModifyAnnotation.value())
            }
        }
        return patchMethodSignureSet
    }
}
