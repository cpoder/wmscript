package com.softwareag.is.wmscript;

import com.softwareag.is.wmscript.compiler.WmScriptCompiler;
import com.softwareag.is.wmscript.compiler.WmScriptCompiler.CompileResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CompilerTest {

    @Test
    void simpleInvoke() {
        String source = "result = invoke pub.string:concat(inString1: firstName, inString2: lastName)\n";
        CompileResult result = WmScriptCompiler.compileToJava(source, "SimpleInvoke", "test");
        assertFalse(result.hasErrors(), "Errors: " + result.errors);
        assertNotNull(result.javaSource);
        assertTrue(result.javaSource.contains("PipelineContext.invoke"));
        assertTrue(result.javaSource.contains("pub.string:concat"));
        System.out.println(result.javaSource);
    }

    @Test
    void destructuring() {
        String source = "{body, header} = invoke pub.client:http(url: endpoint, method: \"POST\")\n";
        CompileResult result = WmScriptCompiler.compileToJava(source, "Destructure", "test");
        assertFalse(result.hasErrors(), "Errors: " + result.errors);
        assertTrue(result.javaSource.contains("extract"));
        assertTrue(result.javaSource.contains("\"body\""));
        assertTrue(result.javaSource.contains("\"header\""));
        System.out.println(result.javaSource);
    }

    @Test
    void ifElse() {
        String source = String.join("\n",
            "if status == \"active\":",
            "    invoke myPkg.svc:process(id: orderId)",
            "elif status == \"pending\":",
            "    invoke myPkg.svc:queue(id: orderId)",
            "else:",
            "    log.warn(\"Unknown status\")",
            "end",
            ""
        );
        CompileResult result = WmScriptCompiler.compileToJava(source, "IfElse", "test");
        assertFalse(result.hasErrors(), "Errors: " + result.errors);
        assertTrue(result.javaSource.contains("if (toBoolean"));
        assertTrue(result.javaSource.contains("else if"));
        assertTrue(result.javaSource.contains("} else {"));
        System.out.println(result.javaSource);
    }

    @Test
    void forLoop() {
        String source = String.join("\n",
            "for order in orders:",
            "    name = order.customerName",
            "    invoke myPkg.svc:process(customer: name)",
            "end",
            ""
        );
        CompileResult result = WmScriptCompiler.compileToJava(source, "ForLoop", "test");
        assertFalse(result.hasErrors(), "Errors: " + result.errors);
        assertTrue(result.javaSource.contains("for (int"));
        assertTrue(result.javaSource.contains("toIterable"));
        System.out.println(result.javaSource);
    }

    @Test
    void tryCatch() {
        String source = String.join("\n",
            "try:",
            "    result = invoke myPkg.svc:riskyOp(input: data)",
            "catch e:",
            "    log.error(\"Failed\")",
            "end",
            ""
        );
        CompileResult result = WmScriptCompiler.compileToJava(source, "TryCatch", "test");
        assertFalse(result.hasErrors(), "Errors: " + result.errors);
        assertTrue(result.javaSource.contains("try {"));
        assertTrue(result.javaSource.contains("catch (Exception e)"));
        System.out.println(result.javaSource);
    }

    @Test
    void nullSafe() {
        String source = "name = order?.customer?.name ?? \"unknown\"\n";
        CompileResult result = WmScriptCompiler.compileToJava(source, "NullSafe", "test");
        assertFalse(result.hasErrors(), "Errors: " + result.errors);
        assertTrue(result.javaSource.contains("getFieldSafe"));
        assertTrue(result.javaSource.contains("coalesce"));
        System.out.println(result.javaSource);
    }

    @Test
    void arrayOps() {
        String source = String.join("\n",
            "ids = orders[].id",
            "active = orders[status == \"active\"]",
            "total = sum(orders[].amount)",
            ""
        );
        CompileResult result = WmScriptCompiler.compileToJava(source, "ArrayOps", "test");
        assertFalse(result.hasErrors(), "Errors: " + result.errors);
        assertTrue(result.javaSource.contains("project"));
        assertTrue(result.javaSource.contains("filter"));
        assertTrue(result.javaSource.contains("sum"));
        System.out.println(result.javaSource);
    }

    @Test
    void fullExample() {
        String source = String.join("\n",
            "// Process orders",
            "for order in orders:",
            "    if order.status == \"active\":",
            "        try:",
            "            response = invoke myPackage.svc:processOrder(order: order)",
            "            results[] = response.confirmation",
            "        catch e:",
            "            log.error(\"Failed processing\")",
            "            failures[] = order.id",
            "        end",
            "    elif order.status == \"pending\":",
            "        invoke myPackage.svc:queueOrder(order: order)",
            "    else:",
            "        skip",
            "    end",
            "end",
            "",
            "if len(failures) > 0:",
            "    raise \"Processing failed\"",
            "end",
            ""
        );
        CompileResult result = WmScriptCompiler.compileToJava(source, "FullExample", "test");
        assertFalse(result.hasErrors(), "Errors: " + result.errors);
        System.out.println("=== FULL EXAMPLE ===");
        System.out.println(result.javaSource);
    }

    @Test
    void helloWorld() throws Exception {
        String source = new String(java.nio.file.Files.readAllBytes(
            java.nio.file.Paths.get("../wmscript-package/ns/wmscript/samples/helloWorld/service.wms")));
        CompileResult result = WmScriptCompiler.compileToJava(source, "helloWorld", "test");
        assertFalse(result.hasErrors(), "Errors: " + result.errors);
        System.out.println("=== HELLO WORLD ===");
        System.out.println(result.javaSource);
    }

    // input/output blocks removed — signature managed via Designer I/O tab

    @Test
    void documentLiteral() {
        String source = String.join("\n",
            "order = {customerId: \"123\", status: \"active\", total: num(amount)}",
            ""
        );
        CompileResult result = WmScriptCompiler.compileToJava(source, "DocLiteral", "test");
        assertFalse(result.hasErrors(), "Errors: " + result.errors);
        assertTrue(result.javaSource.contains("createDocument"));
        System.out.println(result.javaSource);
    }

    @Test
    void arrayLiteral() {
        String source = "items = [\"a\", \"b\", \"c\"]\n";
        CompileResult result = WmScriptCompiler.compileToJava(source, "ArrLiteral", "test");
        assertFalse(result.hasErrors(), "Errors: " + result.errors);
        assertTrue(result.javaSource.contains("new Object[]"));
        System.out.println(result.javaSource);
    }
}
