package com.softwareag.is.wmscript.compiler;

import com.softwareag.is.wmscript.compiler.parser.WmScriptLexer;
import com.softwareag.is.wmscript.compiler.parser.WmScriptParser;
import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.tree.ParseTree;

import java.io.*;
import java.nio.file.*;

/**
 * WmScript compiler: .wms source -> .java source -> .class bytecode
 *
 * Usage:
 *   WmScriptCompiler.compile(sourceFile, outputDir, packageName)
 *
 * The generated Java class follows the IS Java service convention:
 * a static method taking IData pipeline, suitable for invocation via reflection.
 */
public class WmScriptCompiler {

    /** Generated package for compiled WmScript classes */
    public static final String GENERATED_PACKAGE = "wmscript.generated";

    /**
     * Encode an IS namespace name as a valid Java class name.
     * Dots become '_0', colons become '_1', existing underscores become '_2'.
     * This is deterministic, reversible, and collision-free.
     *
     * Examples:
     *   "wmscript.samples:helloWorld" → "wmscript_0samples_1helloWorld"
     *   "my_pkg.folder:svc"          → "my_2pkg_0folder_1svc"
     */
    public static String toClassName(String nsName) {
        StringBuilder sb = new StringBuilder(nsName.length() + 10);
        for (char c : nsName.toCharArray()) {
            switch (c) {
                case '.': sb.append("_0"); break;
                case ':': sb.append("_1"); break;
                case '_': sb.append("_2"); break;
                default:  sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * Parse a standalone WmScript expression and generate Java code for it.
     * Used by template string interpolation.
     */
    public static String compileExpression(String exprSource) {
        CharStream input = CharStreams.fromString(exprSource + "\n");
        WmScriptLexer lexer = new WmScriptLexer(input);
        lexer.removeErrorListeners();
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        WmScriptParser parser = new WmScriptParser(tokens);
        parser.removeErrorListeners();

        // Parse as expression within a dummy assignment: __x = <expr>
        CharStream wrapInput = CharStreams.fromString("__x = " + exprSource + "\n");
        WmScriptLexer wrapLexer = new WmScriptLexer(wrapInput);
        wrapLexer.removeErrorListeners();
        CommonTokenStream wrapTokens = new CommonTokenStream(wrapLexer);
        WmScriptParser wrapParser = new WmScriptParser(wrapTokens);
        wrapParser.removeErrorListeners();

        WmScriptParser.ProgramContext tree = wrapParser.program();
        // Extract the expression from the assignment
        if (tree.statement().size() > 0) {
            var stmt = tree.statement(0);
            if (stmt.assignment() != null && stmt.assignment().simpleAssignment() != null) {
                var expr = stmt.assignment().simpleAssignment().expression();
                JavaCodeGenerator gen = new JavaCodeGenerator("__tmp", "");
                return gen.visit(expr);
            }
        }
        // Fallback: treat as simple variable
        return "ctx.get(\"" + exprSource + "\")";
    }

    /** A declared input or output field from input/output blocks */
    public static class FieldDecl {
        public final String direction; // "input" or "output"
        public final String name;
        public final String type;     // "string", "number", "document", "string[]", etc.
        public final String refPath;  // for ref types: "myPkg.docs:OrderRecord"
        public final java.util.List<FieldDecl> children; // for nested document types

        public FieldDecl(String direction, String name, String type) {
            this(direction, name, type, null, null);
        }

        public FieldDecl(String direction, String name, String type,
                         String refPath, java.util.List<FieldDecl> children) {
            this.direction = direction;
            this.name = name;
            this.type = type;
            this.refPath = refPath;
            this.children = children;
        }

        /** Map WmScript type names to IS field_type values */
        public String isFieldType() {
            String base = type.replace("[]", "");
            switch (base) {
                case "string":   return "string";
                case "number":
                case "int":      return "string";
                case "boolean":  return "string";
                case "document": return "record";
                case "ref":      return "record";
                case "object":   return "object";
                default:         return "string";
            }
        }

        /** IS field_dim: 0 for scalar, 1 for array */
        public String isFieldDim() {
            return type.endsWith("[]") ? "1" : "0";
        }

        public boolean isDocument() {
            return children != null && !children.isEmpty();
        }

        public boolean isRef() {
            return refPath != null;
        }
    }

    public static class CompileResult {
        public final String javaSource;
        public final String className;
        public final String packageName;
        public final java.util.List<String> errors;
        public final java.util.List<String> warnings;
        /** Maps generated Java line numbers to original .wms source line numbers */
        public final java.util.Map<Integer, Integer> sourceMap;
        /** Declared input/output fields (managed via Designer I/O tab) */
        public final java.util.List<FieldDecl> fields;

        public CompileResult(String javaSource, String className, String packageName,
                             java.util.List<String> errors, java.util.List<String> warnings,
                             java.util.Map<Integer, Integer> sourceMap,
                             java.util.List<FieldDecl> fields) {
            this.javaSource = javaSource;
            this.className = className;
            this.packageName = packageName;
            this.errors = errors;
            this.warnings = warnings;
            this.sourceMap = sourceMap != null ? sourceMap : java.util.Collections.emptyMap();
            this.fields = fields != null ? fields : java.util.Collections.emptyList();
        }

        public boolean hasErrors() {
            return !errors.isEmpty();
        }

        /**
         * Look up the original .wms line for a given Java line number.
         * Returns the nearest mapped line at or before the given Java line.
         */
        public int getWmsLine(int javaLine) {
            int best = -1;
            for (var entry : sourceMap.entrySet()) {
                if (entry.getKey() <= javaLine) {
                    best = entry.getValue();
                }
            }
            return best;
        }
    }

    /**
     * Parse and compile a WmScript source string to Java source.
     *
     * @param source      the WmScript source code
     * @param className   the Java class name to generate
     * @param packageName the Java package name
     * @return CompileResult with generated Java source or errors
     */
    public static CompileResult compileToJava(String source, String className, String packageName) {
        java.util.List<String> errors = new java.util.ArrayList<>();
        java.util.List<String> warnings = new java.util.ArrayList<>();

        // Parse
        CharStream input = CharStreams.fromString(source);
        WmScriptLexer lexer = new WmScriptLexer(input);
        lexer.removeErrorListeners();
        lexer.addErrorListener(new CollectingErrorListener(errors));

        CommonTokenStream tokens = new CommonTokenStream(lexer);
        WmScriptParser parser = new WmScriptParser(tokens);
        parser.removeErrorListeners();
        parser.addErrorListener(new CollectingErrorListener(errors));

        WmScriptParser.ProgramContext tree = parser.program();

        if (!errors.isEmpty()) {
            return new CompileResult(null, className, packageName, errors, warnings, null, null);
        }

        // Signature is managed externally (Designer I/O tab), not in the code
        java.util.List<FieldDecl> fields = java.util.Collections.emptyList();

        // Generate Java
        JavaCodeGenerator generator = new JavaCodeGenerator(className, packageName);
        String javaSource = generator.generate(tree);

        return new CompileResult(javaSource, className, packageName, errors, warnings,
                                 generator.getSourceMap(), fields);
    }

    // Signature is managed via Designer I/O tab, not in code.
    // FieldDecl and related classes are kept for API compatibility.

    /**
     * Generate IS service signature JSON from field declarations.
     * Can be used to update the service signature on IS via put_node.
     */
    public static String generateSignatureJson(java.util.List<FieldDecl> fields) {
        StringBuilder inFields = new StringBuilder();
        StringBuilder outFields = new StringBuilder();
        boolean firstIn = true, firstOut = true;

        for (FieldDecl f : fields) {
            StringBuilder target = f.direction.equals("input") ? inFields : outFields;
            boolean isFirst = f.direction.equals("input") ? firstIn : firstOut;
            if (!isFirst) target.append(",\n");
            target.append("        {")
                  .append("\"node_type\": \"field\", ")
                  .append("\"field_name\": \"").append(f.name).append("\", ")
                  .append("\"field_type\": \"").append(f.isFieldType()).append("\", ")
                  .append("\"field_dim\": \"").append(f.isFieldDim()).append("\", ")
                  .append("\"nillable\": \"true\"")
                  .append("}");
            if (f.direction.equals("input")) firstIn = false; else firstOut = false;
        }

        return "{\n"
             + "  \"sig_in\": {\n"
             + "    \"node_type\": \"record\", \"field_type\": \"record\", \"field_dim\": \"0\", \"nillable\": \"true\",\n"
             + "    \"rec_fields\": [\n" + inFields + "\n    ]\n"
             + "  },\n"
             + "  \"sig_out\": {\n"
             + "    \"node_type\": \"record\", \"field_type\": \"record\", \"field_dim\": \"0\", \"nillable\": \"true\",\n"
             + "    \"rec_fields\": [\n" + outFields + "\n    ]\n"
             + "  }\n"
             + "}";
    }

    /**
     * Compile a .wms file to a .java file.
     *
     * @param sourceFile  path to the .wms source file
     * @param outputDir   directory for the generated .java file
     * @param packageName Java package name for the generated class
     * @return the path to the generated .java file
     */
    public static Path compileFile(Path sourceFile, Path outputDir, String packageName) throws IOException {
        String source = Files.readString(sourceFile);

        // Derive class name from file name (e.g., "processOrder.wms" -> "processOrder")
        String fileName = sourceFile.getFileName().toString();
        String className = fileName.endsWith(".wms")
                ? fileName.substring(0, fileName.length() - 4)
                : fileName;

        CompileResult result = compileToJava(source, className, packageName);

        if (result.hasErrors()) {
            StringBuilder sb = new StringBuilder("Compilation errors in " + sourceFile + ":\n");
            for (String error : result.errors) {
                sb.append("  ").append(error).append("\n");
            }
            throw new RuntimeException(sb.toString());
        }

        // Write .java file
        Path packageDir = outputDir;
        if (packageName != null && !packageName.isEmpty()) {
            packageDir = outputDir.resolve(packageName.replace('.', File.separatorChar));
        }
        Files.createDirectories(packageDir);
        Path javaFile = packageDir.resolve(className + ".java");
        Files.writeString(javaFile, result.javaSource);

        // Write source map alongside
        writeSourceMap(packageDir.resolve(className + ".sourcemap.json"),
                       sourceFile.getFileName().toString(), result.sourceMap);

        return javaFile;
    }

    /**
     * Full compile: .wms -> .java -> .class
     */
    public static Path compileToClass(Path sourceFile, Path outputDir, String packageName,
                                       String classpath) throws Exception {
        // Step 1: .wms -> .java
        Path javaFile = compileFile(sourceFile, outputDir, packageName);

        // Step 2: .java -> .class (using javac, target Java 11 for IS compatibility)
        ProcessBuilder pb = new ProcessBuilder(
                "javac",
                "--release", "11",
                "-cp", classpath,
                "-d", outputDir.toString(),
                javaFile.toString()
        );
        pb.redirectErrorStream(true);
        Process process = pb.start();
        String output = new String(process.getInputStream().readAllBytes());
        int exitCode = process.waitFor();

        if (exitCode != 0) {
            throw new RuntimeException("javac compilation failed:\n" + output);
        }

        return javaFile;
    }

    /**
     * Write a source map file mapping Java lines to WmScript lines.
     * Format: simple JSON for easy consumption by VS Code and other tools.
     */
    static void writeSourceMap(Path mapFile, String sourceFile,
                                java.util.Map<Integer, Integer> sourceMap) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"source\": \"").append(sourceFile).append("\",\n");
        sb.append("  \"mappings\": {\n");
        boolean first = true;
        for (var entry : sourceMap.entrySet()) {
            if (!first) sb.append(",\n");
            sb.append("    \"").append(entry.getKey()).append("\": ").append(entry.getValue());
            first = false;
        }
        sb.append("\n  }\n");
        sb.append("}\n");
        Files.writeString(mapFile, sb.toString());
    }

    // --- Error listener ---
    private static class CollectingErrorListener extends BaseErrorListener {
        private final java.util.List<String> errors;

        CollectingErrorListener(java.util.List<String> errors) {
            this.errors = errors;
        }

        @Override
        public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol,
                                int line, int charPositionInLine, String msg,
                                RecognitionException e) {
            errors.add("line " + line + ":" + charPositionInLine + " " + msg);
        }
    }

    /**
     * Compile all .wms files in an IS package directory.
     * Scans ns/ recursively for service.wms files, derives class names from
     * namespace paths, and compiles to code/classes/.
     *
     * @param packageDir  root of the IS package (contains ns/, code/, etc.)
     * @param isHome      path to webMethods installation (for IS JAR classpath)
     * @return list of compiled service paths
     */
    public static java.util.List<String> compilePackage(Path packageDir, String isHome) throws Exception {
        Path nsDir = packageDir.resolve("ns");
        Path classesDir = packageDir.resolve("code").resolve("classes");
        Files.createDirectories(classesDir);

        // Build classpath from IS installation + package jars
        String classpath = buildIsClasspath(isHome, packageDir);

        java.util.List<String> compiled = new java.util.ArrayList<>();
        java.util.List<String> errors = new java.util.ArrayList<>();

        // Walk ns/ looking for service.wms files
        if (!Files.exists(nsDir)) {
            throw new RuntimeException("No ns/ directory found in " + packageDir);
        }

        Files.walk(nsDir)
            .filter(p -> p.getFileName().toString().equals("service.wms"))
            .forEach(wmsFile -> {
                try {
                    // Derive IS namespace name from path:
                    // ns/wmscript/samples/helloWorld/service.wms
                    //    -> wmscript.samples:helloWorld
                    Path relPath = nsDir.relativize(wmsFile.getParent());
                    String nsName = deriveNsName(relPath);
                    String className = toClassName(nsName);

                    System.out.println("Compiling: " + nsName + " -> " + className);

                    // Step 1: .wms -> .java
                    String source = Files.readString(wmsFile);
                    CompileResult result = compileToJava(source, className, GENERATED_PACKAGE);

                    if (result.hasErrors()) {
                        for (String err : result.errors) {
                            errors.add(nsName + ": " + err);
                            System.err.println("  ERROR: " + err);
                        }
                        return;
                    }

                    // Write .java file and source map
                    Path pkgDir = classesDir.resolve(GENERATED_PACKAGE.replace('.', File.separatorChar));
                    Files.createDirectories(pkgDir);
                    Path javaFile = pkgDir.resolve(className + ".java");
                    Files.writeString(javaFile, result.javaSource);
                    writeSourceMap(pkgDir.resolve(className + ".sourcemap.json"),
                                   wmsFile.getFileName().toString(), result.sourceMap);

                    // Write signature file (for VS Code to push to IS)
                    if (!result.fields.isEmpty()) {
                        String sigJson = generateSignatureJson(result.fields);
                        Files.writeString(pkgDir.resolve(className + ".signature.json"), sigJson);
                    }

                    // Step 2: .java -> .class (target Java 11 for IS compatibility)
                    ProcessBuilder pb = new ProcessBuilder(
                            "javac",
                            "--release", "11",
                            "-cp", classpath,
                            "-d", classesDir.toString(),
                            javaFile.toString()
                    );
                    pb.redirectErrorStream(true);
                    Process process = pb.start();
                    String output = new String(process.getInputStream().readAllBytes());
                    int exitCode = process.waitFor();

                    if (exitCode != 0) {
                        errors.add(nsName + ": javac failed:\n" + output);
                        System.err.println("  JAVAC ERROR: " + output);
                    } else {
                        compiled.add(nsName);
                        System.out.println("  OK: " + className + ".class");
                    }
                } catch (Exception e) {
                    errors.add(wmsFile + ": " + e.getMessage());
                    System.err.println("  EXCEPTION: " + e.getMessage());
                }
            });

        if (!errors.isEmpty()) {
            System.err.println("\n" + errors.size() + " error(s):");
            for (String err : errors) {
                System.err.println("  " + err);
            }
            if (compiled.isEmpty()) {
                throw new RuntimeException("All compilations failed");
            }
        }

        System.out.println("\nCompiled " + compiled.size() + " service(s)");
        return compiled;
    }

    /**
     * Derive IS namespace name from a path relative to ns/.
     * ns/wmscript/samples/helloWorld -> wmscript.samples:helloWorld
     */
    static String deriveNsName(Path relPath) {
        int count = relPath.getNameCount();
        if (count < 2) {
            return relPath.toString().replace(File.separatorChar, '.');
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count - 1; i++) {
            if (i > 0) sb.append('.');
            sb.append(relPath.getName(i));
        }
        sb.append(':').append(relPath.getName(count - 1));
        return sb.toString();
    }

    /**
     * Build classpath for javac from IS home + package jars.
     */
    static String buildIsClasspath(String isHome, Path packageDir) {
        java.util.List<String> entries = new java.util.ArrayList<>();

        // IS server lib
        Path isServerJar = Paths.get(isHome, "IntegrationServer", "lib", "wm-isserver.jar");
        if (Files.exists(isServerJar)) entries.add(isServerJar.toString());

        // IS client lib
        Path isClientJar = Paths.get(isHome, "common", "lib", "wm-isclient.jar");
        if (Files.exists(isClientJar)) entries.add(isClientJar.toString());

        Path glueJar = Paths.get(isHome, "common", "lib", "glue.jar");
        if (Files.exists(glueJar)) entries.add(glueJar.toString());

        // Package jars (static and regular)
        Path jarsDir = packageDir.resolve("code").resolve("jars");
        addJarsFromDir(entries, jarsDir);
        addJarsFromDir(entries, jarsDir.resolve("static"));

        // Package classes dir
        entries.add(packageDir.resolve("code").resolve("classes").toString());

        return String.join(File.pathSeparator, entries);
    }

    private static void addJarsFromDir(java.util.List<String> entries, Path dir) {
        if (Files.exists(dir)) {
            try {
                Files.list(dir)
                    .filter(p -> p.toString().endsWith(".jar"))
                    .forEach(p -> entries.add(p.toString()));
            } catch (IOException e) {
                // ignore
            }
        }
    }

    // --- CLI entry point ---
    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            printUsage();
            System.exit(1);
        }

        String command = args[0];

        switch (command) {
            case "compile": {
                if (args.length < 3) {
                    System.err.println("Usage: wmscript compile <source.wms> -o <outputDir> [-p <package>] [-cp <classpath>]");
                    System.exit(1);
                }
                Path sourceFile = Paths.get(args[1]);
                String outputDir = null, packageName = GENERATED_PACKAGE, classpath = null;
                for (int i = 2; i < args.length; i++) {
                    switch (args[i]) {
                        case "-o": outputDir = args[++i]; break;
                        case "-p": packageName = args[++i]; break;
                        case "-cp": classpath = args[++i]; break;
                    }
                }
                if (outputDir == null) {
                    System.err.println("Missing -o <outputDir>");
                    System.exit(1);
                }
                if (classpath != null) {
                    Path result = compileToClass(sourceFile, Paths.get(outputDir), packageName, classpath);
                    System.out.println("Compiled: " + result);
                } else {
                    Path result = compileFile(sourceFile, Paths.get(outputDir), packageName);
                    System.out.println("Generated: " + result);
                }
                break;
            }
            case "compile-package": {
                if (args.length < 2) {
                    System.err.println("Usage: wmscript compile-package <packageDir> --is-home <path>");
                    System.exit(1);
                }
                Path packageDir = Paths.get(args[1]);
                String isHome = null;
                for (int i = 2; i < args.length; i++) {
                    if ("--is-home".equals(args[i])) isHome = args[++i];
                }
                if (isHome == null) {
                    System.err.println("Missing --is-home <path>");
                    System.exit(1);
                }
                compilePackage(packageDir, isHome);
                break;
            }
            default:
                printUsage();
                System.exit(1);
        }
    }

    private static void printUsage() {
        System.err.println("WmScript Compiler");
        System.err.println("Usage:");
        System.err.println("  wmscript compile <source.wms> -o <outputDir> [-p <package>] [-cp <classpath>]");
        System.err.println("  wmscript compile-package <packageDir> --is-home <isInstallDir>");
    }
}
