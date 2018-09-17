package com.meituan.robust.autopatch

import com.meituan.robust.Constants
import com.meituan.robust.patch.annotaion.Add
import com.meituan.robust.patch.annotaion.Modify
import com.meituan.robust.utils.JavaUtils
import javassist.*
import javassist.expr.ExprEditor
import javassist.expr.MethodCall
import org.codehaus.groovy.GroovyException
import org.gradle.api.logging.Logger
import robust.gradle.plugin.AutoPatchTransform

class ReadAnnotation {
    static Logger logger
    static int index = 0

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
            method.instrument(new ExprEditor() {
                @Override
                void edit(MethodCall m) throws CannotCompileException {
                    try {
                        println "find modify methods : " + method.longName +
                            " :: " +
                            m.method.declaringClass.name +
                            "; " +
                            m.methodName
                        if (Constants.LAMBDA_MODIFY == m.method.declaringClass.name) {
                            isAllMethodsPatch = false
                            addPatchMethodAndModifiedClass(patchMethodSignureSet, method)
                        } else if (m.methodName.contains("lambda\$")) {
                            m.method.instrument(new ExprEditor() {
                                @Override
                                void edit(MethodCall mm) throws CannotCompileException {
                                    try {
                                        println "!! find modify methods : " + m.method.longName +
                                            " :: " +
                                            mm.method.declaringClass.name +
                                            "; " +
                                            mm.methodName

                                        if (Constants.LAMBDA_MODIFY == mm.method.declaringClass.name) {
                                            // Config.methodMap.put(m.method.longName, Config.methodMap.size() + 1)
                                            def oC = method.getDeclaringClass()
                                            oC.removeMethod(method)
                                            def newMethod = CtNewMethod.copy(m.method,
                                                method.getName(), method.getDeclaringClass(), null)
                                            oC.addMethod(newMethod)
                                            isAllMethodsPatch = false
                                            addPatchMethodAndModifiedClass(patchMethodSignureSet,
                                                newMethod)
                                        }
                                    } catch (Exception e) {
                                        println "this is a exception " + e.getMessage()
                                    }
                                }
                            })
                        }
                    } catch (NotFoundException e) {
                        e.printStackTrace()
                        logger.warn("  cannot find class  " + method.longName +
                            " line number " +
                            m.lineNumber +
                            " this class may never used ,please remove this class")
                    }
                }
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

    /*static boolean findModifyMethod(CtMethod method, Set patchMethodSignureSet) {
        boolean isAllMethodsPatch = true
        method.instrument(new ExprEditor() {
            @Override
            void edit(MethodCall m) throws CannotCompileException {
                try {
                    println "find modify methods : " + method.longName + " :: " + m.method.declaringClass.name + "; " + m.methodName
                    if (Constants.LAMBDA_MODIFY == m.method.declaringClass.name) {
                        isAllMethodsPatch = false
                        addPatchMethodAndModifiedClass(patchMethodSignureSet, method)
                    } else if (m.methodName.contains("lambda\$")) {
//                        findModifyMethod(m.method)
                        m.method.instrument(new ExprEditor() {
                            @Override
                            void edit(MethodCall mm) throws CannotCompileException {
                                println "find modify methods : " + m.method.longName + " :: " + mm.method.declaringClass.name + "; " + mm.methodName
                                if (Constants.LAMBDA_MODIFY == mm.method.declaringClass.name) {
                                    isAllMethodsPatch = false
                                    try {
                                        addPatchMethodAndModifiedClass(patchMethodSignureSet, m.method)
                                    } catch (Exception e) {
                                        println "this is a exception " + e.getMessage()
                                    }
                                }
                            }
                        })
                    }
                } catch (NotFoundException e) {
                    e.printStackTrace()
                    logger.warn("  cannot find class  " + method.longName +
                        " line number " +
                        m.lineNumber +
                        " this class may never used ,please remove this class")
                }
            }
        })

        return isAllMethodsPatch
    }*/

    static Set addPatchMethodAndModifiedClass(Set patchMethodSignureSet, CtMethod method) {
        if (Config.methodMap.get(method.longName) == null) {
            print("addPatchMethodAndModifiedClass pint methodmap ")
            JavaUtils.printMap(Config.methodMap)
            /*if (method.longName.contains("lambda\$")) {
                Config.methodMap.put(method.longName, Config.methodMap.size() + 1)
            } else {
                throw new GroovyException("patch method " + method.longName +
                    " haven't insert code by Robust.Cannot patch this method, method.signature  " +
                    method.signature +
                    "  ")
            }*/
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
