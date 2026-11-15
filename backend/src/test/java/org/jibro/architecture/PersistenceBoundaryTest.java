package org.jibro.architecture;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.asm.ClassReader;
import org.springframework.asm.ClassVisitor;
import org.springframework.asm.FieldVisitor;
import org.springframework.asm.MethodVisitor;
import org.springframework.asm.Opcodes;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.core.type.filter.RegexPatternTypeFilter;
import org.springframework.stereotype.Controller;
import org.springframework.stereotype.Service;

/** Checks compiled application roles without starting Spring or requiring a database. */
class PersistenceBoundaryTest {
    private static final List<String> DATABASE_TYPES = List.of(
            "org/springframework/jdbc/", "com/querydsl/", "jakarta/persistence/EntityManager",
            "jakarta/persistence/Query", "javax/sql/DataSource", "java/sql/");

    @Test
    void servicesControllersAndSecurityConfigurationUsePersistenceBoundaries() throws IOException {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Service.class));
        scanner.addIncludeFilter(new AnnotationTypeFilter(Controller.class));
        scanner.addIncludeFilter(new RegexPatternTypeFilter(Pattern.compile("org\\.jibro\\.global\\.config\\.SecurityConfig")));
        var candidates = scanner.findCandidateComponents("org.jibro");
        assertFalse(candidates.isEmpty(), "The application role scan must not be empty");
        var violations = new ArrayList<String>();
        for (var candidate : candidates) {
            String className = candidate.getBeanClassName();
            for (String dependency : databaseDependencies(className))
                violations.add(className + " -> " + dependency);
        }
        assertTrue(violations.isEmpty(), "Move database execution into a domain Repository/Store: " + violations);
    }

    @Test
    void boundaryCheckAlsoDetectsDatabaseCallsCreatedInsideMethods() throws IOException {
        assertFalse(databaseDependencies(DirectDatabaseAccess.class.getName()).isEmpty());
    }

    private static List<String> databaseDependencies(String className) throws IOException {
        var dependencies = new TreeSet<String>();
        var resource = className.replace('.', '/') + ".class";
        try (var bytecode = PersistenceBoundaryTest.class.getClassLoader().getResourceAsStream(resource)) {
            if (bytecode == null) throw new IOException("Missing compiled class: " + className);
            new ClassReader(bytecode).accept(new ClassVisitor(Opcodes.ASM9) {
                private void inspect(String reference) {
                    if (reference == null) return;
                    for (String type : DATABASE_TYPES)
                        if (reference.contains(type)) dependencies.add(reference.replace('/', '.'));
                }

                @Override
                public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
                    inspect(descriptor);
                    inspect(signature);
                    return null;
                }

                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                    inspect(descriptor);
                    inspect(signature);
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override
                        public void visitTypeInsn(int opcode, String type) {
                            inspect(type);
                        }

                        @Override
                        public void visitFieldInsn(int opcode, String owner, String field, String descriptor) {
                            inspect(owner);
                            inspect(descriptor);
                        }

                        @Override
                        public void visitMethodInsn(int opcode, String owner, String method, String descriptor, boolean isInterface) {
                            inspect(owner);
                            inspect(descriptor);
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
        return List.copyOf(dependencies);
    }

    private static class DirectDatabaseAccess {
        Integer query() {
            return new org.springframework.jdbc.core.JdbcTemplate().queryForObject("SELECT 1", Integer.class);
        }
    }
}
