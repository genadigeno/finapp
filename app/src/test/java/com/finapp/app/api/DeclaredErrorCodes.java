package com.finapp.app.api;

import com.finapp.platform.api.ErrorCode;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Every error code the production code declares, found by scanning rather than by a list.
 *
 * <p>Codes belong to the modules that raise them, so no single module sees the whole taxonomy —
 * which is why this reads the classpath instead of enumerating a known set. A list here would be
 * one more thing to remember to update, and forgetting would make both the registry check and the
 * published contract quietly incomplete.
 */
final class DeclaredErrorCodes {

    private DeclaredErrorCodes() {}

    static List<ErrorCode> all() {
        List<ErrorCode> codes = new ArrayList<>();
        for (JavaClass javaClass : implementations()) {
            Class<?> loaded = javaClass.reflect();
            if (!loaded.isEnum()) {
                continue;
            }
            for (Object constant : loaded.getEnumConstants()) {
                codes.add((ErrorCode) constant);
            }
        }
        return codes;
    }

    static List<JavaClass> implementations() {
        return new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.finapp")
                .stream()
                .filter(javaClass -> javaClass.isAssignableTo(ErrorCode.class))
                .filter(javaClass -> !javaClass.getName().equals(ErrorCode.class.getName()))
                .toList();
    }
}
