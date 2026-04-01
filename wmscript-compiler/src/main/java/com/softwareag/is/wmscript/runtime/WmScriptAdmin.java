package com.softwareag.is.wmscript.runtime;

import com.wm.app.b2b.server.PackageManager;
import com.wm.app.b2b.server.Server;
import com.wm.app.b2b.server.Service;
import com.wm.app.b2b.server.ServiceException;
import com.wm.app.b2b.server.ns.Namespace;
import com.wm.data.*;
import com.wm.lang.ns.NSName;
import com.wm.lang.ns.NSNode;
import com.softwareag.is.wmscript.compiler.WmScriptCompiler;

import java.io.*;
import java.nio.file.*;

/**
 * Admin services for WmScript — called remotely from VS Code.
 * These are standard IS Java services (static methods taking IData).
 *
 * Services:
 *   wmscript.admin:getSource      — read .wms source from IS filesystem
 *   wmscript.admin:saveSource     — write .wms source + compile
 *   wmscript.admin:createService  — create new WmScript service (node.ndf + .wms + compile)
 */
public class WmScriptAdmin {

    /**
     * Get WmScript source code for a service.
     * Input:  servicePath (string) — e.g. "wmscript.samples:helloWorld"
     * Output: source (string) — the .wms file content
     */
    public static void getSource(IData pipeline) throws Exception {
        IDataCursor c = pipeline.getCursor();
        String servicePath = IDataUtil.getString(c, "servicePath");
        c.destroy();

        if (servicePath == null || servicePath.isEmpty()) {
            throw new ServiceException("servicePath is required");
        }

        File wmsFile = resolveWmsFile(servicePath);
        if (!wmsFile.exists()) {
            throw new ServiceException("WmScript source not found: " + wmsFile.getAbsolutePath());
        }

        String source = new String(Files.readAllBytes(wmsFile.toPath()), "UTF-8");

        IDataCursor out = pipeline.getCursor();
        IDataUtil.put(out, "source", source);
        out.destroy();
    }

    /**
     * Save WmScript source code and compile it.
     * Input:  servicePath (string), source (string)
     * Output: status (string), errors (string[])
     */
    public static void saveSource(IData pipeline) throws Exception {
        IDataCursor c = pipeline.getCursor();
        String servicePath = IDataUtil.getString(c, "servicePath");
        String source = IDataUtil.getString(c, "source");
        c.destroy();

        if (servicePath == null || source == null) {
            throw new ServiceException("servicePath and source are required");
        }

        // Write the .wms file
        File wmsFile = resolveWmsFile(servicePath);
        if (!wmsFile.getParentFile().exists()) {
            throw new ServiceException("Service directory not found: " + wmsFile.getParent());
        }
        Files.write(wmsFile.toPath(), source.getBytes("UTF-8"));

        // Compile: .wms → .java → .class
        String[] compileErrors = compileService(servicePath, source);

        // Reload the package so IS picks up the new compiled class
        NSNode node = Namespace.current().getNode(NSName.create(servicePath));
        if (node != null) {
            String pkgName = ((com.wm.app.b2b.server.Package) node.getPackage()).getName();
            try {
                Service.doInvoke(NSName.create("wm.server.packages:packageReload"),
                    IDataFactory.create("package", pkgName));
            } catch (Exception e) {
                Server.logError(e);
            }
        }

        IDataCursor out = pipeline.getCursor();
        if (compileErrors.length == 0) {
            IDataUtil.put(out, "status", "compiled");
        } else {
            IDataUtil.put(out, "status", "error");
            IDataUtil.put(out, "errors", compileErrors);
        }
        out.destroy();
    }

    /**
     * Create a new WmScript service with initial source.
     * Input:  servicePath (string), packageName (string), source (string)
     * Output: status (string), errors (string[])
     */
    public static void createService(IData pipeline) throws Exception {
        IDataCursor c = pipeline.getCursor();
        String servicePath = IDataUtil.getString(c, "servicePath");
        String packageName = IDataUtil.getString(c, "packageName");
        String source = IDataUtil.getString(c, "source");
        c.destroy();

        if (servicePath == null || packageName == null) {
            throw new ServiceException("servicePath and packageName are required");
        }
        if (source == null || source.isEmpty()) {
            source = "// " + servicePath.substring(servicePath.indexOf(':') + 1)
                   + " - WmScript service\n//\n// @input name : string\n// @output greeting : string\n\n"
                   + "greeting = invoke pub.string:concat(inString1: \"Hello, \", inString2: name)\n";
        }

        // Find the package directory
        com.wm.app.b2b.server.Package pkg = PackageManager.getPackage(packageName);
        if (pkg == null) {
            throw new ServiceException("Package not found: " + packageName);
        }
        File pkgDir = pkg.getStore().getPackageDir();

        // Build the filesystem path for the service
        // servicePath = "folder.sub:svcName" → ns/folder/sub/svcName/
        String fsRel = servicePath.replace('.', File.separatorChar).replace(':', File.separatorChar);
        File svcDir = new File(pkgDir, "ns" + File.separator + fsRel);

        if (svcDir.exists()) {
            throw new ServiceException("Service already exists: " + servicePath);
        }
        svcDir.mkdirs();

        // Write node.ndf
        String nodeNdf = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n\n"
            + "<Values version=\"2.0\">\n"
            + "  <value name=\"svc_type\">wmscript</value>\n"
            + "  <value name=\"svc_subtype\">default</value>\n"
            + "  <record name=\"svc_sig\" javaclass=\"com.wm.util.Values\">\n"
            + "    <record name=\"sig_in\" javaclass=\"com.wm.util.Values\">\n"
            + "      <value name=\"node_type\">record</value>\n"
            + "      <value name=\"field_type\">record</value>\n"
            + "      <value name=\"field_dim\">0</value>\n"
            + "      <value name=\"nillable\">true</value>\n"
            + "    </record>\n"
            + "    <record name=\"sig_out\" javaclass=\"com.wm.util.Values\">\n"
            + "      <value name=\"node_type\">record</value>\n"
            + "      <value name=\"field_type\">record</value>\n"
            + "      <value name=\"field_dim\">0</value>\n"
            + "      <value name=\"nillable\">true</value>\n"
            + "    </record>\n"
            + "  </record>\n"
            + "  <value name=\"stateless\">yes</value>\n"
            + "  <value name=\"is_public\">true</value>\n"
            + "</Values>";
        Files.write(new File(svcDir, "node.ndf").toPath(), nodeNdf.getBytes("UTF-8"));

        // Write service.wms
        File wmsFile = new File(svcDir, "service.wms");
        Files.write(wmsFile.toPath(), source.getBytes("UTF-8"));

        // Write Designer marker file (.wmscriptservice) so Designer recognizes the service type
        new File(svcDir, "wmscriptservice.wmscriptservice").createNewFile();

        // Ensure target package depends on WmScript (for PipelineContext at runtime).
        // Write directly to manifest.v3 if not already present.
        if (!"WmScript".equals(packageName)) {
            addManifestDependency(pkgDir, "WmScript");
        }

        // Compile (pass pkgDir directly — node isn't in namespace yet)
        String[] compileErrors = compileServiceInDir(servicePath, source, pkgDir);

        // Reload package AFTER compile so IS picks up both the node and the .class
        try {
            Service.doInvoke(NSName.create("wm.server.packages:packageReload"),
                IDataFactory.create("package", packageName));
        } catch (Exception e) {
            Server.logError(e);
        }

        IDataCursor out = pipeline.getCursor();
        if (compileErrors.length == 0) {
            IDataUtil.put(out, "status", "created");
        } else {
            IDataUtil.put(out, "status", "created_with_errors");
            IDataUtil.put(out, "errors", compileErrors);
        }
        out.destroy();
    }

    // ================================================================
    // Internal helpers
    // ================================================================

    /**
     * Resolve the .wms file path for a service via IS namespace.
     */
    private static File resolveWmsFile(String servicePath) throws ServiceException {
        NSNode node = Namespace.current().getNode(NSName.create(servicePath));
        if (node == null) {
            throw new ServiceException("Service not found in namespace: " + servicePath);
        }
        com.wm.app.b2b.server.Package pkg = (com.wm.app.b2b.server.Package) node.getPackage();
        File nodeDir = pkg.getStore().getNodePath(node.getNSName());
        return new File(nodeDir, "service.wms");
    }

    /**
     * Compile a WmScript service by looking up its package via namespace.
     * Used by saveSource (service already loaded in IS).
     */
    private static String[] compileService(String servicePath, String source) {
        NSNode node = Namespace.current().getNode(NSName.create(servicePath));
        if (node == null) {
            return new String[]{"Service not found in namespace: " + servicePath};
        }
        com.wm.app.b2b.server.Package pkg = (com.wm.app.b2b.server.Package) node.getPackage();
        File pkgDir = pkg.getStore().getPackageDir();
        return compileServiceInDir(servicePath, source, pkgDir);
    }

    /**
     * Compile a WmScript service: parse source → generate Java → javac → .class
     * Takes pkgDir directly (works for both existing and newly created services).
     */
    private static String[] compileServiceInDir(String servicePath, String source, File pkgDir) {
        try {
            String className = WmScriptCompiler.toClassName(servicePath);
            String packageName = WmScriptCompiler.GENERATED_PACKAGE;

            // Parse + generate Java
            WmScriptCompiler.CompileResult result =
                WmScriptCompiler.compileToJava(source, className, packageName);

            if (result.hasErrors()) {
                return result.errors.toArray(new String[0]);
            }

            Path classesDir = new File(pkgDir, "code/classes").toPath();
            Files.createDirectories(classesDir);

            // Write .java
            Path pkgPath = classesDir.resolve(packageName.replace('.', File.separatorChar));
            Files.createDirectories(pkgPath);
            Path javaFile = pkgPath.resolve(className + ".java");
            Files.write(javaFile, result.javaSource.getBytes("UTF-8"));

            // Build classpath from IS installation
            // watt.server.homeDir = .../IntegrationServer/instances/default
            // IS libs are at .../IntegrationServer/lib/
            // Common libs are at .../common/lib/
            String isHome = System.getProperty("watt.server.homeDir", "");
            File isHomeDir = new File(isHome);
            File isInstall = isHomeDir.getParentFile().getParentFile(); // up to IntegrationServer/
            File sagInstall = isInstall.getParentFile(); // up to wm/

            StringBuilder cp = new StringBuilder();
            addToClasspath(cp, new File(isInstall, "lib/wm-isserver.jar").getAbsolutePath());
            addToClasspath(cp, new File(sagInstall, "common/lib/wm-isclient.jar").getAbsolutePath());
            addToClasspath(cp, new File(sagInstall, "common/lib/glue.jar").getAbsolutePath());
            // Add target package's jars
            addJarsFromDir(cp, new File(pkgDir, "code/jars"));
            addToClasspath(cp, classesDir.toString());

            // Add WmScript package's jars (contains PipelineContext runtime)
            com.wm.app.b2b.server.Package wmsPkg = PackageManager.getPackage("WmScript");
            if (wmsPkg != null) {
                File wmsPkgDir = wmsPkg.getStore().getPackageDir();
                addJarsFromDir(cp, new File(wmsPkgDir, "code/jars"));
                addToClasspath(cp, new File(wmsPkgDir, "code/classes").getAbsolutePath());
            }

            // javac (target Java 11 for compatibility)
            ProcessBuilder pb = new ProcessBuilder(
                "javac", "--release", "11",
                "-cp", cp.toString(),
                "-d", classesDir.toString(),
                javaFile.toString()
            );
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            String output = new String(proc.getInputStream().readAllBytes());
            int exitCode = proc.waitFor();

            if (exitCode != 0) {
                return new String[]{"javac failed: " + output};
            }

            return new String[0]; // success

        } catch (Exception e) {
            return new String[]{e.getMessage()};
        }
    }

    /**
     * Update the node.ndf's svc_sig section with fields from input/output blocks.
     * Supports nested documents, arrays, and document type references.
     */
    private static void updateNodeSignature(String servicePath,
            java.util.List<WmScriptCompiler.FieldDecl> fields, File pkgDir) {
        try {
            String fsRel = servicePath.replace('.', File.separatorChar).replace(':', File.separatorChar);
            File nodeNdf = new File(pkgDir, "ns" + File.separator + fsRel + File.separator + "node.ndf");
            if (!nodeNdf.exists()) { return; }

            StringBuilder sigInFields = new StringBuilder();
            StringBuilder sigOutFields = new StringBuilder();
            for (WmScriptCompiler.FieldDecl f : fields) {
                StringBuilder target = "input".equals(f.direction) ? sigInFields : sigOutFields;
                renderFieldXml(target, f, "        ");
            }

            String nodeNdfContent = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n\n"
                + "<Values version=\"2.0\">\n"
                + "  <value name=\"svc_type\">wmscript</value>\n"
                + "  <value name=\"svc_subtype\">default</value>\n"
                + "  <record name=\"svc_sig\" javaclass=\"com.wm.util.Values\">\n"
                + "    <record name=\"sig_in\" javaclass=\"com.wm.util.Values\">\n"
                + "      <value name=\"node_type\">record</value>\n"
                + "      <value name=\"node_subtype\">unknown</value>\n"
                + "      <value name=\"field_type\">record</value>\n"
                + "      <value name=\"field_dim\">0</value>\n"
                + "      <value name=\"nillable\">true</value>\n"
                + "      <array name=\"rec_fields\" type=\"record\" depth=\"1\">\n"
                + sigInFields
                + "      </array>\n"
                + "    </record>\n"
                + "    <record name=\"sig_out\" javaclass=\"com.wm.util.Values\">\n"
                + "      <value name=\"node_type\">record</value>\n"
                + "      <value name=\"node_subtype\">unknown</value>\n"
                + "      <value name=\"field_type\">record</value>\n"
                + "      <value name=\"field_dim\">0</value>\n"
                + "      <value name=\"nillable\">true</value>\n"
                + "      <array name=\"rec_fields\" type=\"record\" depth=\"1\">\n"
                + sigOutFields
                + "      </array>\n"
                + "    </record>\n"
                + "  </record>\n"
                + "  <value name=\"stateless\">yes</value>\n"
                + "  <value name=\"is_public\">true</value>\n"
                + "</Values>";

            Files.write(nodeNdf.toPath(), nodeNdfContent.getBytes("UTF-8"));

        } catch (Exception e) {
            Server.logError(e);
        }
    }

    /**
     * Render a FieldDecl to IS node.ndf XML, handling nested documents and refs.
     */
    private static void renderFieldXml(StringBuilder sb, WmScriptCompiler.FieldDecl f, String indent) {
        sb.append(indent).append("<record javaclass=\"com.wm.util.Values\">\n");
        String i = indent + "  ";

        if (f.isDocument()) {
            // Nested document or document list
            sb.append(i).append("<value name=\"node_type\">record</value>\n");
            sb.append(i).append("<value name=\"node_subtype\">unknown</value>\n");
            sb.append(i).append("<value name=\"field_name\">").append(f.name).append("</value>\n");
            sb.append(i).append("<value name=\"field_type\">record</value>\n");
            sb.append(i).append("<value name=\"field_dim\">").append(f.isFieldDim()).append("</value>\n");
            sb.append(i).append("<value name=\"nillable\">true</value>\n");
            sb.append(i).append("<array name=\"rec_fields\" type=\"record\" depth=\"1\">\n");
            for (WmScriptCompiler.FieldDecl child : f.children) {
                renderFieldXml(sb, child, i + "  ");
            }
            sb.append(i).append("</array>\n");
        } else if (f.isRef()) {
            // Document type reference
            sb.append(i).append("<value name=\"node_type\">record</value>\n");
            sb.append(i).append("<value name=\"node_subtype\">unknown</value>\n");
            sb.append(i).append("<value name=\"field_name\">").append(f.name).append("</value>\n");
            sb.append(i).append("<value name=\"field_type\">record</value>\n");
            sb.append(i).append("<value name=\"field_dim\">").append(f.isFieldDim()).append("</value>\n");
            sb.append(i).append("<value name=\"nillable\">true</value>\n");
            sb.append(i).append("<value name=\"rec_ref\">").append(f.refPath).append("</value>\n");
        } else {
            // Scalar field
            sb.append(i).append("<value name=\"node_type\">field</value>\n");
            sb.append(i).append("<value name=\"node_subtype\">unknown</value>\n");
            sb.append(i).append("<value name=\"field_name\">").append(f.name).append("</value>\n");
            sb.append(i).append("<value name=\"field_type\">").append(f.isFieldType()).append("</value>\n");
            sb.append(i).append("<value name=\"field_dim\">").append(f.isFieldDim()).append("</value>\n");
            sb.append(i).append("<value name=\"nillable\">true</value>\n");
        }

        sb.append(indent).append("</record>\n");
    }

    private static void addToClasspath(StringBuilder cp, String path) {
        if (new File(path).exists()) {
            if (cp.length() > 0) cp.append(File.pathSeparator);
            cp.append(path);
        }
    }

    /**
     * Add a dependency to a package's manifest.v3 if not already present.
     * Replaces <null name="requires"/> or adds to existing <record name="requires">.
     */
    private static void addManifestDependency(File pkgDir, String depPackage) {
        try {
            File manifest = new File(pkgDir, "manifest.v3");
            if (!manifest.exists()) { return; }
            String content = new String(Files.readAllBytes(manifest.toPath()), "UTF-8");

            // Already has this dependency?
            if (content.contains("\"" + depPackage + "\"")) { return; }

            String depEntry = "  <record name=\"requires\" javaclass=\"com.wm.util.Values\">\n"
                            + "    <value name=\"" + depPackage + "\">0.1.0</value>\n"
                            + "  </record>";

            if (content.contains("<null name=\"requires\"/>")) {
                // Replace null requires with our dependency
                content = content.replace("<null name=\"requires\"/>", depEntry);
            } else if (content.contains("<record name=\"requires\"")) {
                // Add to existing requires block
                content = content.replace("</record>\n  <",
                    "  <value name=\"" + depPackage + "\">0.1.0</value>\n  </record>\n  <");
            } else {
                // No requires section — add before closing </Values>
                content = content.replace("</Values>", depEntry + "\n</Values>");
            }

            Files.write(manifest.toPath(), content.getBytes("UTF-8"));
        } catch (Exception e) {
            Server.logError(e);
        }
    }

    private static void addJarsFromDir(StringBuilder cp, File dir) {
        if (dir != null && dir.exists()) {
            File[] jars = dir.listFiles((d, n) -> n.endsWith(".jar"));
            if (jars != null) {
                for (File jar : jars) {
                    addToClasspath(cp, jar.getAbsolutePath());
                }
            }
        }
    }
}
