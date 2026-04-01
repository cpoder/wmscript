package com.softwareag.is.wmscript.runtime;

import com.wm.app.b2b.server.BaseService;
import com.wm.app.b2b.server.Package;
import com.wm.app.b2b.server.ServerClassLoader;
import com.wm.app.b2b.server.ServiceSetupException;
import com.wm.app.b2b.server.ServiceException;
import com.wm.data.IData;
import com.wm.lang.ns.NSName;
import com.wm.lang.ns.NSServiceType;
import com.wm.util.Values;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;

/**
 * IS service type for WmScript.
 * Loads pre-compiled .class files produced by the WmScript compiler (run outside IS).
 * Execution model is identical to JavaService: reflection-based static method invocation.
 *
 * Compilation happens externally (VS Code plugin or CLI):
 *   service.wms -> WmScript compiler -> .java -> javac -> .class
 *
 * The .class files must be in code/classes/wmscript/generated/ before the service is invoked.
 */
public class WmScriptService extends BaseService {

    private static final NSServiceType SVC_TYPE =
            NSServiceType.create("wmscript", "default");

    /** Must match WmScriptCompiler.GENERATED_PACKAGE */
    private static final String GENERATED_PACKAGE = "wmscript.generated";

    // Resolved compiled method
    private Class<?> compiledClass;
    private Method compiledMethod;
    private ServerClassLoader loader;
    private boolean valid = false;
    private final Package pkg;

    public WmScriptService(Package pkg, NSName name, Values nodeDef) {
        super(pkg, name, SVC_TYPE);
        this.pkg = pkg;
        if (nodeDef != null) {
            this.setNodeValues(nodeDef);
        }
    }

    @Override
    public void initializeService() {
        try {
            if (!valid) {
                validate();
            }
        } catch (Exception e) {
            // Log but don't throw (IS convention — service will fail on first invoke)
            com.wm.app.b2b.server.Server.logError(e);
        }
    }

    /**
     * Load the pre-compiled class from code/classes/.
     * Uses the same class name encoding as WmScriptCompiler.toClassName().
     */
    public void validate() throws ServiceSetupException {
        try {
            this.loader = ServerClassLoader.getPackageLoader(this.getPackageName());
            String className = toClassName(this.getNSName().getFullName());
            String fqClassName = GENERATED_PACKAGE + "." + className;

            try {
                this.compiledClass = this.loader.loadClass(fqClassName);
            } catch (ClassNotFoundException e) {
                throw new ServiceSetupException(
                        "Compiled WmScript class not found: " + fqClassName + ". "
                        + "Run the WmScript compiler to generate .class files. "
                        + "Expected in: code/classes/" + GENERATED_PACKAGE.replace('.', '/') + "/" + className + ".class");
            }

            this.compiledMethod = this.compiledClass.getMethod("main", IData.class);

            if (!Modifier.isStatic(this.compiledMethod.getModifiers())) {
                throw new ServiceSetupException(
                        "Generated WmScript class " + fqClassName + " has non-static main method");
            }

            this.valid = true;

        } catch (ServiceSetupException e) {
            throw e;
        } catch (Exception e) {
            throw new ServiceSetupException("WmScript validation failed: " + e.getMessage());
        }
    }

    @Override
    public IData baseInvoke(IData pipeline) throws Exception {
        if (!valid || (loader != null && loader.isDefunct())) {
            validate();
        }

        try {
            compiledMethod.invoke(null, pipeline);
        } catch (InvocationTargetException e) {
            Throwable target = e.getTargetException();
            throw mapException(target);
        }

        return pipeline;
    }

    @Override
    public Values baseInvoke(Values pipeline) throws Exception {
        IData result = baseInvoke((IData) pipeline);
        return (result instanceof Values) ? (Values) result : Values.use(result);
    }

    // ================================================================
    // Error line mapping: generated Java line → .wms source line
    // ================================================================

    /**
     * Map an exception from generated code back to .wms source lines.
     * Scans the stack trace for the generated class, looks up the
     * nearest @wms:N comment in the .java source, and wraps the
     * exception with a message referencing the .wms line number.
     */
    private Exception mapException(Throwable target) {
        String genClassName = GENERATED_PACKAGE + "."
                + toClassName(this.getNSName().getFullName());
        String serviceName = this.getNSName().getFullName();

        for (StackTraceElement frame : target.getStackTrace()) {
            if (genClassName.equals(frame.getClassName())) {
                int javaLine = frame.getLineNumber();
                if (javaLine > 0) {
                    int wmsLine = lookupWmsLine(javaLine);
                    if (wmsLine > 0) {
                        String msg = target.getMessage();
                        if (msg == null) msg = target.getClass().getSimpleName();
                        ServiceException mapped = new ServiceException(
                                "[" + serviceName + " line " + wmsLine + "] " + msg);
                        mapped.initCause(target);
                        return mapped;
                    }
                }
                break; // found the generated class frame, stop searching
            }
        }

        // No mapping found — return original
        if (target instanceof Exception) return (Exception) target;
        return new RuntimeException(target);
    }

    /**
     * Look up the .wms line number for a given Java line number.
     * Reads the generated .java file and finds the nearest @wms:N
     * comment at or before the given line.
     */
    private int lookupWmsLine(int javaLine) {
        try {
            String className = toClassName(this.getNSName().getFullName());
            File classesDir = new File(pkg.getStore().getPackageDir(), "code/classes");
            File javaFile = new File(classesDir,
                    GENERATED_PACKAGE.replace('.', File.separatorChar)
                    + File.separator + className + ".java");

            if (!javaFile.exists()) return -1;

            String[] lines = new String(
                    Files.readAllBytes(javaFile.toPath()), "UTF-8").split("\n");
            int bestWmsLine = -1;
            int limit = Math.min(javaLine, lines.length);
            for (int i = 0; i < limit; i++) {
                String line = lines[i].trim();
                if (line.startsWith("// @wms:")) {
                    try {
                        bestWmsLine = Integer.parseInt(
                                line.substring("// @wms:".length()).trim());
                    } catch (NumberFormatException nfe) {
                        // ignore
                    }
                }
            }
            return bestWmsLine;
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * Encode IS namespace name as Java class name.
     * Must match WmScriptCompiler.toClassName().
     */
    static String toClassName(String nsName) {
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
}
