package ro.redeul.google.go.actions;


import com.google.common.collect.Lists;
import com.intellij.ide.SaveAndSyncHandlerImpl;
import com.intellij.ide.highlighter.JavaFileType;
import com.intellij.lang.java.JavaLanguage;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.LangDataKeys;
import com.intellij.openapi.actionSystem.PlatformDataKeys;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Condition;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.psi.*;
import com.intellij.psi.impl.PsiElementFactoryImpl;
import com.intellij.psi.impl.PsiManagerEx;
import com.intellij.psi.impl.cache.TypeInfo;
import com.intellij.psi.impl.java.stubs.PsiAnnotationStub;
import com.intellij.psi.impl.java.stubs.impl.PsiJavaFileStubImpl;
import com.intellij.psi.impl.java.stubs.impl.PsiMethodStubImpl;
import com.intellij.psi.stubs.StubElement;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.io.StringRef;
import org.apache.commons.lang.StringUtils;
import ro.redeul.google.go.lang.psi.GoFile;
import ro.redeul.google.go.lang.psi.GoPackage;
import ro.redeul.google.go.lang.psi.GoParenthesizedExprOrType;
import ro.redeul.google.go.lang.psi.GoPsiElement;
import ro.redeul.google.go.lang.psi.declarations.GoConstDeclaration;
import ro.redeul.google.go.lang.psi.declarations.GoConstDeclarations;
import ro.redeul.google.go.lang.psi.declarations.GoVarDeclaration;
import ro.redeul.google.go.lang.psi.expressions.GoExpr;
import ro.redeul.google.go.lang.psi.expressions.GoPrimaryExpression;
import ro.redeul.google.go.lang.psi.expressions.GoUnaryExpression;
import ro.redeul.google.go.lang.psi.expressions.binary.*;
import ro.redeul.google.go.lang.psi.expressions.literals.GoLiteral;
import ro.redeul.google.go.lang.psi.expressions.literals.GoLiteralFunction;
import ro.redeul.google.go.lang.psi.expressions.literals.GoLiteralIdentifier;
import ro.redeul.google.go.lang.psi.expressions.literals.composite.GoLiteralComposite;
import ro.redeul.google.go.lang.psi.expressions.literals.composite.GoLiteralCompositeElement;
import ro.redeul.google.go.lang.psi.expressions.literals.composite.GoLiteralCompositeValue;
import ro.redeul.google.go.lang.psi.expressions.primary.*;
import ro.redeul.google.go.lang.psi.statements.*;
import ro.redeul.google.go.lang.psi.statements.select.GoSelectCommClauseDefault;
import ro.redeul.google.go.lang.psi.statements.select.GoSelectCommClauseRecv;
import ro.redeul.google.go.lang.psi.statements.select.GoSelectCommClauseSend;
import ro.redeul.google.go.lang.psi.statements.select.GoSelectStatement;
import ro.redeul.google.go.lang.psi.statements.switches.*;
import ro.redeul.google.go.lang.psi.toplevel.*;
import ro.redeul.google.go.lang.psi.types.*;
import ro.redeul.google.go.lang.psi.types.struct.GoTypeStructAnonymousField;
import ro.redeul.google.go.lang.psi.types.struct.GoTypeStructField;
import ro.redeul.google.go.lang.psi.typing.GoType;
import ro.redeul.google.go.lang.psi.visitors.GoRecursiveElementVisitor;
import ro.redeul.google.go.util.GoNumber;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class GoAwayAction extends AnAction {

    @Override
    public void actionPerformed(AnActionEvent e) {
        FileDocumentManager.getInstance().saveAllDocuments();

        Editor editor = e.getData(PlatformDataKeys.EDITOR);
        PsiFile psiFile = e.getData(LangDataKeys.PSI_FILE);
        if (psiFile == null || editor == null)
            return;

        TranslatingVisitor visitor = new TranslatingVisitor(e.getProject());
        ((GoFile) psiFile).accept(visitor);
        File destDir = new File(new File(psiFile.getVirtualFile().getPath()).getParentFile(), "java");
        destDir.mkdirs();
        visitor.emit(destDir);

        SaveAndSyncHandlerImpl.refreshOpenFiles();

        VirtualFileManager.getInstance().refreshWithoutFileWatcher(true);
    }


    private static class TranslatingVisitor extends BaseVisitor {
        private final Project project;
        private final PsiElementFactoryImpl psiElementFactory;
        private final Map<String, JavaPackage> javaPackages = new HashMap<>();

        public TranslatingVisitor(Project project) {
            this.project = project;
            psiElementFactory = new PsiElementFactoryImpl((PsiManagerEx) PsiManagerEx.getInstance(project));
        }

        @Override
        public void visitFile(GoFile file) {
            String packageName = file.getFullPackageName();
            JavaPackage javaPackage = javaPackages.get(packageName);
            if (javaPackage == null) {
                javaPackage = new JavaPackage(packageName);
                javaPackages.put(packageName, javaPackage);
            }

            JVMElementFactory topLevelFactory = JVMElementFactories.getFactory(JavaLanguage.INSTANCE, project);
            if (topLevelFactory == null) throw new NullPointerException();
            file.acceptChildren(new FileVisitor(javaPackage, topLevelFactory));
        }

        public void emit(File destDir) {
            for (JavaPackage javaPackage : javaPackages.values()) {
                javaPackage.emit(project, destDir);
            }
        }
    }


    private static class FileVisitor extends BaseVisitor {
        private final JavaPackage javaPackage;

        private final Map<String, String> importAliases = new HashMap<>();
        private final JVMElementFactory topLevelFactory;

        public FileVisitor(JavaPackage javaPackage, JVMElementFactory topLevelFactory) {
            this.javaPackage = javaPackage;
            this.topLevelFactory = topLevelFactory;
        }

        @Override
        public void visitPackageDeclaration(GoPackageDeclaration declaration) {
        }

        @Override
        public void visitImportDeclarations(GoImportDeclarations declarations) {
            for (GoImportDeclaration goImportDeclaration : declarations.getDeclarations()) {
                visitImportDeclaration(goImportDeclaration);
            }
        }

        @Override
        public void visitImportDeclaration(GoImportDeclaration declaration) {
            importAliases.put(declaration.getPackageAlias(), declaration.getPackageName());
        }

        @Override
        public void visitConstDeclarations(GoConstDeclarations declarations) {
            for (GoConstDeclaration goConstDeclaration : declarations.getDeclarations()) {
                visitConstDeclaration(goConstDeclaration);
            }
        }

        @Override
        public void visitConstDeclaration(GoConstDeclaration declaration) {
            for (GoLiteralIdentifier goLiteralIdentifier : declaration.getIdentifiers()) {
                GoExpr expression = declaration.getExpression(goLiteralIdentifier);
                ExpressionVisitor expressionVisitor = new ExpressionVisitor();
                expression.accept(expressionVisitor);
                javaPackage.addConst(goLiteralIdentifier.getName(), expressionVisitor.getText());
            }
            super.visitConstDeclaration(declaration);
        }

        @Override
        public void visitTypeDeclaration(GoTypeDeclaration declaration) {
            for (GoTypeSpec goTypeSpec : declaration.getTypeSpecs()) {
                String typeName = goTypeSpec.getTypeNameDeclaration().getName();
                System.out.println("Type declaration: " + typeName);
                GoPsiType type = goTypeSpec.getType();
                boolean isInterface = (type instanceof GoPsiTypeInterface);
                final JavaClass javaClass = new JavaClass(typeName, isInterface);
                javaPackage.addClass(javaClass);
                declaration.acceptChildren(new BaseVisitor() {
                    @Override
                    public void visitFunctionDeclaration(GoFunctionDeclaration declaration) {
                        javaClass.addMethod(new JavaMethod(declaration.getName(), true, ""));
                    }

                    @Override
                    public void visitTypeSpec(GoTypeSpec type) {
                        super.visitTypeSpec(type);
                    }

                    @Override
                    public void visitTypeStructField(GoTypeStructField field) {
                        String type = field.getType().getText();
                        if (type.equals("string")) type = "String";

                        for (GoLiteralIdentifier goLiteralIdentifier : field.getIdentifiers()) {
                            String name = goLiteralIdentifier.getName();
                            javaClass.addField(new JavaField(name, type, isPrivate(name)));
                        }
                        System.out.println("field " + field.getText() + " on " + javaClass.name);
                        super.visitTypeStructField(field);
                    }
                });
            }
        }

        @Override
        public void visitMethodDeclaration(GoMethodDeclaration declaration) {
            JavaClass javaClass = javaPackage.javaClasses.get(declaration.getMethodReceiver().getType().getName());
            final StringBuilder buf = new StringBuilder();

            GoFunctionParameter[] parameters = declaration.getParameters();
            for (int i = 0; i < parameters.length; i++) {
                GoFunctionParameter parameter = parameters[i];
                GoPsiType type = parameter.getType();
                for (GoLiteralIdentifier goLiteralIdentifier : parameter.getIdentifiers()) {
                    buf.append(type).append(" ").append(goLiteralIdentifier.getText()).append(", ");
                }
            }

            if (buf.length() > 0) buf.setLength(buf.length() - 2);
            final JavaMethod javaMethod = new JavaMethod(declaration.getFunctionName(), false, buf.toString());

            buf.setLength(0);
            for (GoStatement goStatement : declaration.getBlock().getStatements()) {
                StatementVisitor statementVisitor = new StatementVisitor();
                goStatement.accept(statementVisitor);
                statementVisitor.write(buf, true);
            }
            javaMethod.setBody(buf.toString());
            javaClass.addMethod(javaMethod);
        }

        private class StatementVisitor extends BaseVisitor {
            private StringBuilder text = new StringBuilder();

            @Override
            public void visitElement(GoPsiElement element) {
                super.visitElement(element);
            }

            @Override
            public void visitAssignment(GoAssignmentStatement statement) {
                super.visitAssignment(statement);
            }

            @Override
            public void visitCallOrConvExpression(GoCallOrConvExpression expression) {
                GoPrimaryExpression baseExpression = expression.getBaseExpression();
                ExpressionVisitor leftExpression = new ExpressionVisitor();
                baseExpression.accept(leftExpression);
                text.append(leftExpression.getText());

                text.append("(");
                GoExpr[] arguments = expression.getArguments();
                for (int i = 0; i < arguments.length; i++) {
                    GoExpr goExpr = arguments[i];
                    ExpressionVisitor paramExpression = new ExpressionVisitor();
                    goExpr.accept(paramExpression);
                    if (i > 0) text.append(", ");
                    text.append(paramExpression.getText());
                }
                text.append(")");

            }

            @Override
            public void visitShortVarDeclaration(GoShortVarDeclaration declaration) {
                GoLiteralIdentifier[] declarations = declaration.getDeclarations();
                int declarationCount = declarations.length;
                GoLiteralIdentifier lastDeclaration = declarations[declarationCount - 1];
                if (lastDeclaration.getName().equals("err")) {
                    declarationCount--;
                }

                GoExpr[] expressions = declaration.getExpressions();
                int expressionCount = expressions.length;

                if (expressionCount == declarationCount) {
                    for (int i = 0; i < declarationCount; i++) {
                        String expr = eval(expressions[i]);
                        text.append(declaration.getText()).append(" = ").append(expr).append(";\n");
                    }
                }
            }

            @Override
            public void visitIfStatement(GoIfStatement statement) {
                if (statement.getExpression().getText().matches("^\\s*err\\s*!=\\s*nil\\s*$")) {
//                    text.append("// skipping dumb go error handling");
                    System.out.println("Skipping: " + statement.getText().replaceAll("\\s+", " "));
                    return;
                }
                text.append("if (").append(eval(statement.getExpression())).append(") {\n");
                text.append(eval(statement.getThenBlock())).append(";\n");

                GoBlockStatement elseBlock = statement.getElseBlock();
                if (elseBlock != null) {
                    text.append("} else {\n");
                    text.append(eval(elseBlock)).append(";\n");
                }

                text.append("}\n");

                super.visitIfStatement(statement);
            }

            @Override
            public void visitRelationalExpression(GoRelationalExpression expression) {
                super.visitRelationalExpression(expression);
            }

            public void write(StringBuilder buf, boolean completeStatement) {
                buf.append(text.toString());
                if (completeStatement && text.length() > 0) buf.append(";\n");
            }
        }

        private String eval(GoBlockStatement blockStatement) {
            StatementVisitor statementVisitor = new StatementVisitor();
            blockStatement.accept(statementVisitor);
            return statementVisitor.text.toString();
        }

        private String eval(GoExpr expression) {
            ExpressionVisitor expressionVisitor = new ExpressionVisitor();
            expression.accept(expressionVisitor);
            return expressionVisitor.getText();
        }

        private class ExpressionVisitor extends BaseVisitor {
            private final StringBuilder buf = new StringBuilder();

            @Override
            public void visitSelectorExpression(GoSelectorExpression expression) {
                final List<String> parts = new ArrayList<>();
                while (true) {
                    parts.add(expression.getIdentifier().getText());
                    GoPrimaryExpression baseExpression = expression.getBaseExpression();
                    if (baseExpression instanceof GoSelectorExpression) {
                        expression = (GoSelectorExpression) baseExpression;
                    } else if (baseExpression instanceof GoLiteralExpression) {
                        GoLiteralExpression literalExpression = (GoLiteralExpression) baseExpression;
                        GoLiteral literal = literalExpression.getLiteral();
                        if (literal instanceof GoLiteralIdentifier) {
                            GoMethodDeclaration methodDeclaration = (GoMethodDeclaration) PsiTreeUtil.findFirstParent(expression, new Condition<PsiElement>() {
                                @Override
                                public boolean value(PsiElement psiElement) {
                                    return psiElement instanceof GoMethodDeclaration;
                                }
                            });
                            if (literal.getText().equals(methodDeclaration.getMethodReceiver().getIdentifier().getName())) {
//                                parts.add("this");
                            } else {
                                parts.add(literalExpression.getText());
                            }
                        } else {
                            parts.add(literalExpression.getText());
                        }
                        break;
                    } else {
                        System.out.println("huh? " + baseExpression.getClass().getName());
                        parts.add(baseExpression.getText());
                    }
                }

                parts.set(0, toLowerCase(parts.get(0)));
                buf.append(StringUtils.join(Lists.reverse(parts), "."));
//                super.visitSelectorExpression(expression);
            }

            @Override
            public void visitLiteralExpression(GoLiteralExpression expression) {
                buf.append(expression.getText());
            }

            public String getText() {
                return buf.toString();
            }
        }
    }

    private static class JavaPackage {
        private String goPackageName;
        private final Map<String, String> constants = new HashMap<>();
        private final Map<String, JavaClass> javaClasses = new HashMap<>();

        public JavaPackage(String packageName) {
            this.goPackageName = packageName;
        }

        public void addConst(String name, String expression) {
            constants.put(name, expression);
        }

        public void addClass(JavaClass javaClass) {
            javaClasses.put(javaClass.getName(), javaClass);
        }

        public void emit(Project project, File destDir) {
            for (JavaClass javaClass : javaClasses.values()) {
                javaClass.emit(project, this, destDir);
            }
        }

        public String getJavaPackageName() {
            final StringBuilder buf = new StringBuilder();
            String[] parts = goPackageName.split("/");
            String[] domainParts = parts[0].split("\\.");
            for (int i = domainParts.length - 1; i >= 0; i--) {
                buf.append(domainParts[i]);
                if (i != 0) buf.append(".");
            }
            for (int i = parts.length - 1; i >= 0; i--) {
                buf.append(parts[i]);
                if (i != 0) buf.append(".");
            }
            return buf.toString().replaceAll("-", "_");
        }
    }

    private static class JavaClass {
        private final String name;
        private final boolean isInterface;
        private final Map<String, JavaMethod> methods = new HashMap<>();
        private final Map<String, JavaField> fields = new HashMap<>();

        public JavaClass(String name, boolean isInterface) {
            this.name = name;
            this.isInterface = isInterface;
        }

        public String getName() {
            return name;
        }

        public void addMethod(JavaMethod javaMethod) {
            System.out.println("Add " + getName() + "." + javaMethod.getName());
            methods.put(javaMethod.getName(), javaMethod);
        }

        public void addField(JavaField javaField) {
            System.out.println("Add " + getName() + "." + javaField.getName());
            fields.put(javaField.getName(), javaField);
        }

        public void build() {
        }

        public void emit(Project project, JavaPackage javaPackage, File destDir) {
            String name = this.name;
            if (isPrivate(name)) {
                name = "Private" + name;
            }
            final File file = new File(destDir, name + ".java");
            final StringBuilder buf = new StringBuilder();
            buf.append("package ").append(javaPackage.getJavaPackageName()).append(";\n\n");

            buf.append("public class ").append(getName()).append("{\n");
            for (JavaField javaField : fields.values()) {
                javaField.write(buf);
            }
            for (JavaMethod javaMethod : methods.values()) {
                javaMethod.write(buf);
            }

            buf.append("}\n");

//            Document document = EditorFactory.getInstance().createDocument(buf.toString());
//            PsiFile psiFile = PsiDocumentManager.getInstance(project).getPsiFile(document);
//
//            AbstractLayoutCodeProcessor processor = new ReformatCodeProcessor(project, psiFile, null, false);
////            processor = new OptimizeImportsProcessor(processor, psiFile);
////            processor = new RearrangeCodeProcessor(processor);
//            processor.run();

            try {
                BufferedWriter out = new BufferedWriter(new FileWriter(file));
                out.write(buf.toString());
                out.close();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }


//            if (Character.isUpperCase(getName().charAt(0))) {
//            } else {
//                System.out.println("Skipping private class " + getName());
//            }
        }
    }

    private static boolean isPrivate(String name) {
        return Character.isLowerCase(name.charAt(0));
    }

    private static String toLowerCase(String s) {
        return s.substring(0, 1).toLowerCase() + s.substring(1);
    }

    private static class JavaMethod {
        private final String name;
        private final String parameters;
        private final boolean isAbstract;
        private PsiCodeBlock codeBlock;
        private String body;

        public JavaMethod(String name, boolean isAbstract, String parameters) {
            this.name = name;
            this.parameters = parameters;
            this.isAbstract = isAbstract;
        }

        public String getName() {
            return name;
        }

        public void setCodeBlock(PsiCodeBlock codeBlock) {
            this.codeBlock = codeBlock;
        }

        public void setBody(String body) {
            this.body = body;
        }

        public void write(StringBuilder buf) {
            String visibility = isPrivate(name) ? "public" : "private";
            buf.append(visibility).append(" void ").append(toLowerCase(name)).append("(").append(parameters).append(") {\n")
                    .append(body)
                    .append("}");
        }
    }

    private static class JavaField {
        private final String name;
        private final String type;
        private final boolean isPrivate;

        public JavaField(String name, String type, boolean isPrivate) {
            this.name = name;
            this.type = type;
            this.isPrivate = isPrivate;
        }

        public String getName() {
            return name;
        }

        public void write(StringBuilder buf) {
            String visibility = isPrivate ? "public" : "private";
            buf.append(visibility).append(" ").append(type).append(" ").append(name).append(";\n");
        }

    }

    private static class BaseVisitor extends GoRecursiveElementVisitor {
        protected void warn(PsiElement element) {
            System.out.println("huh? " + getClass().getName() + " doesn't expect a " + element.getClass().getSimpleName() + ": " + element.getText().replaceAll("\n", "↵"));
        }

        @Override
        public void visitElement(PsiElement element) {
            warn(element);
            super.visitElement(element);
        }

        @Override
        public void visitElement(GoPsiElement element) {
            warn(element);
            super.visitElement(element);
        }
    }


    private static class Garbage extends GoRecursiveElementVisitor {
        private final PsiElementFactoryImpl psiElementFactory;
        private PsiElement parentElement;
        private StubElement parentStub;
        private Project project;

        public Garbage(Project project) {
            this.project = project;
            psiElementFactory = new PsiElementFactoryImpl((PsiManagerEx) PsiManagerEx.getInstance(project));
        }

        @Override
        public void visitElement(PsiElement element) {
            System.out.println(element.getClass().getName());
            parentElement.add(element);
            element.acceptChildren(this);
        }

        @Override
        public void visitElement(GoPsiElement element) {
            System.out.println(element.getClass().getName());
            parentElement.add(element);
            element.acceptChildren(this);
        }

        @Override
        public void visitFile(GoFile file) {
            PsiJavaFile javaFile = (PsiJavaFile) PsiFileFactory.getInstance(project).createFileFromText(file.getName().replaceAll("\\.go", ".java"), JavaFileType.INSTANCE, "");
            parentElement = javaFile;
            parentStub = new PsiJavaFileStubImpl(javaFile, StringRef.fromString("pkg"), false);
            file.acceptChildren(this);
        }

        @Override
        public void visitTypeName(GoPsiTypeName typeName) {
            super.visitTypeName(typeName);
        }

        @Override
        public void visitPackageDeclaration(GoPackageDeclaration declaration) {
            super.visitPackageDeclaration(declaration);
        }

        @Override
        public void visitImportDeclarations(GoImportDeclarations declarations) {
            super.visitImportDeclarations(declarations);
        }

        @Override
        public void visitImportDeclaration(GoImportDeclaration declaration) {
            super.visitImportDeclaration(declaration);
        }

        @Override
        public void visitMethodDeclaration(GoMethodDeclaration declaration) {
            GoType[] returnTypes = declaration.getReturnTypes();
            GoType returnType = returnTypes.length == 1 ? returnTypes[0] : GoType.Nil;
            TypeInfo returnTypeInfo = new TypeInfo(returnType.toString(), (byte) 0, false, new PsiAnnotationStub[0]);
            PsiMethodStubImpl methodStub = new PsiMethodStubImpl(parentStub, StringRef.fromString(declaration.getFunctionName()), returnTypeInfo, (byte) 0, StringRef.fromString("defaultValueText"));
//            parentElement.add(new PsiMethodImpl(methodStub));

            PsiMethod javaMethod = psiElementFactory.createMethod(declaration.getFunctionName(), new PsiPrimitiveType("int", new PsiAnnotation[0]));
//            javaMethod.getBody()
            parentElement.add(javaMethod);
            PsiElement myParent = parentElement;
            parentElement = javaMethod;

            try {
                declaration.acceptChildren(this);
            } finally {
                parentElement = myParent;
            }
        }

        @Override
        public void visitFunctionDeclaration(GoFunctionDeclaration declaration) {
            GoType[] returnTypes = declaration.getReturnTypes();
            GoType returnType = returnTypes.length == 1 ? returnTypes[0] : GoType.Nil;
            TypeInfo returnTypeInfo = new TypeInfo(returnType.toString(), (byte) 0, false, new PsiAnnotationStub[0]);
            PsiMethodStubImpl methodStub = new PsiMethodStubImpl(parentStub, StringRef.fromString(declaration.getFunctionName()), returnTypeInfo, (byte) 0, StringRef.fromString("defaultValueText"));
//            parentElement.add(new PsiMethodImpl(methodStub));

            PsiMethod javaMethod = psiElementFactory.createMethod(declaration.getFunctionName(), new PsiPrimitiveType("int", new PsiAnnotation[0]));
            parentElement.add(javaMethod);
            PsiElement myParent = parentElement;
            parentElement = javaMethod;

            try {
                declaration.acceptChildren(this);
            } finally {
                parentElement = myParent;
            }
        }

        @Override
        public void visitTypeDeclaration(GoTypeDeclaration declaration) {
            super.visitTypeDeclaration(declaration);
        }

        @Override
        public void visitTypeSpec(GoTypeSpec type) {
            super.visitTypeSpec(type);
        }

        @Override
        public void visitArrayType(GoPsiTypeArray type) {
            super.visitArrayType(type);
        }

        @Override
        public void visitSliceType(GoPsiTypeSlice type) {
            super.visitSliceType(type);
        }

        @Override
        public void visitMapType(GoPsiTypeMap type) {
            super.visitMapType(type);
        }

        @Override
        public void visitChannelType(GoPsiTypeChannel type) {
            super.visitChannelType(type);
        }

        @Override
        public void visitPointerType(GoPsiTypePointer type) {
            super.visitPointerType(type);
        }

        @Override
        public void visitTypeNameDeclaration(GoTypeNameDeclaration declaration) {
            super.visitTypeNameDeclaration(declaration);
        }

        @Override
        public void visitLiteralIdentifier(GoLiteralIdentifier identifier) {
            super.visitLiteralIdentifier(identifier);
        }

        @Override
        public void visitFunctionParameter(GoFunctionParameter parameter) {
            super.visitFunctionParameter(parameter);
        }

        @Override
        public void visitLiteralExpression(GoLiteralExpression expression) {
            super.visitLiteralExpression(expression);
        }

        @Override
        public void visitConstDeclarations(GoConstDeclarations declarations) {
            super.visitConstDeclarations(declarations);
        }

        @Override
        public void visitConstDeclaration(GoConstDeclaration declaration) {
            super.visitConstDeclaration(declaration);
        }

        @Override
        public void visitFunctionLiteral(GoLiteralFunction literal) {
            super.visitFunctionLiteral(literal);
        }

        @Override
        public void visitForWithRange(GoForWithRangeStatement statement) {
            super.visitForWithRange(statement);
        }

        @Override
        public void visitForWithRangeAndVars(GoForWithRangeAndVarsStatement statement) {
            super.visitForWithRangeAndVars(statement);
        }

        @Override
        public void visitForWithClauses(GoForWithClausesStatement statement) {
            super.visitForWithClauses(statement);
        }

        @Override
        public void visitForWithCondition(GoForWithConditionStatement statement) {
            super.visitForWithCondition(statement);
        }

        @Override
        public void visitShortVarDeclaration(GoShortVarDeclaration declaration) {
            super.visitShortVarDeclaration(declaration);
        }

        @Override
        public void visitIndexExpression(GoIndexExpression expression) {
            super.visitIndexExpression(expression);
        }

        @Override
        public void visitLiteralCompositeVal(GoLiteralCompositeValue compositeValue) {
            super.visitLiteralCompositeVal(compositeValue);
        }

        @Override
        public void visitLiteralComposite(GoLiteralComposite composite) {
            super.visitLiteralComposite(composite);
        }

        @Override
        public void visitIfStatement(GoIfStatement statement) {
            super.visitIfStatement(statement);
        }

        @Override
        public void visitGoStatement(GoGoStatement statement) {
            super.visitGoStatement(statement);
        }

        @Override
        public void visitDeferStatement(GoDeferStatement statement) {
            super.visitDeferStatement(statement);
        }

        @Override
        public void visitBuiltinCallExpression(GoBuiltinCallOrConversionExpression expression) {
            super.visitBuiltinCallExpression(expression);
        }

        @Override
        public void visitLiteral(GoLiteral literal) {
            super.visitLiteral(literal);
        }

        @Override
        public void visitLiteralBool(GoLiteral<Boolean> literal) {
            super.visitLiteralBool(literal);
        }

        @Override
        public void visitLiteralInteger(GoLiteral<BigInteger> literal) {
            super.visitLiteralInteger(literal);
        }

        @Override
        public void visitReturnStatement(GoReturnStatement statement) {
            parentElement.add(statement);

        }

        @Override
        public void visitCallOrConvExpression(GoCallOrConvExpression expression) {
            super.visitCallOrConvExpression(expression);
        }

        @Override
        public void visitMethodReceiver(GoMethodReceiver receiver) {
            System.out.println("Receiver is " + receiver.getText());
        }

        @Override
        public void visitStructType(GoPsiTypeStruct type) {
            super.visitStructType(type);
        }

        @Override
        public void visitInterfaceType(GoPsiTypeInterface type) {
            super.visitInterfaceType(type);
        }

        @Override
        public void visitFunctionType(GoPsiTypeFunction type) {
            super.visitFunctionType(type);
        }

        @Override
        public void visitTypeStructField(GoTypeStructField field) {
            super.visitTypeStructField(field);
        }

        @Override
        public void visitFunctionParameterList(GoFunctionParameterList list) {
            System.out.println("Parameters are " + list.getText());
        }

        @Override
        public void visitTypeStructAnonymousField(GoTypeStructAnonymousField field) {
            super.visitTypeStructAnonymousField(field);
        }

        @Override
        public void visitLiteralCompositeElement(GoLiteralCompositeElement element) {
            super.visitLiteralCompositeElement(element);
        }

        @Override
        public void visitLabeledStatement(GoLabeledStatement statement) {
            super.visitLabeledStatement(statement);
        }

        @Override
        public void visitBlockStatement(GoBlockStatement statement) {
            PsiElement myParent = parentElement;
            parentElement = psiElementFactory.createCodeBlock();
            myParent.add(parentElement);
            try {
                statement.acceptChildren(this);
            } finally {
                parentElement = myParent;
            }
        }

        @Override
        public void visitBreakStatement(GoBreakStatement statement) {
            super.visitBreakStatement(statement);
        }

        @Override
        public void visitContinueStatement(GoContinueStatement statement) {
            super.visitContinueStatement(statement);
        }

        @Override
        public void visitSelectorExpression(GoSelectorExpression expression) {
            super.visitSelectorExpression(expression);
        }

        @Override
        public void visitGotoStatement(GoGotoStatement statement) {
            super.visitGotoStatement(statement);
        }

        @Override
        public void visitSwitchExpressionClause(GoSwitchExpressionClause statement) {
            super.visitSwitchExpressionClause(statement);
        }

        @Override
        public void visitSwitchTypeClause(GoSwitchTypeClause statement) {
            super.visitSwitchTypeClause(statement);
        }

        @Override
        public void visitSwitchTypeStatement(GoSwitchTypeStatement statement) {
            super.visitSwitchTypeStatement(statement);
        }

        @Override
        public void visitSwitchExpressionStatement(GoSwitchExpressionStatement statement) {
            super.visitSwitchExpressionStatement(statement);
        }

        @Override
        public void visitSwitchTypeGuard(GoSwitchTypeGuard typeGuard) {
            super.visitSwitchTypeGuard(typeGuard);
        }

        @Override
        public void visitSelectStatement(GoSelectStatement statement) {
            super.visitSelectStatement(statement);
        }

        @Override
        public void visitSelectCommClauseDefault(GoSelectCommClauseDefault commClause) {
            super.visitSelectCommClauseDefault(commClause);
        }

        @Override
        public void visitSelectCommClauseRecv(GoSelectCommClauseRecv commClause) {
            super.visitSelectCommClauseRecv(commClause);
        }

        @Override
        public void visitSelectCommClauseSend(GoSelectCommClauseSend commClause) {
            super.visitSelectCommClauseSend(commClause);
        }

        @Override
        public void visitSliceExpression(GoSliceExpression expression) {
            super.visitSliceExpression(expression);
        }

        @Override
        public void visitSendStatement(GoSendStatement statement) {
            super.visitSendStatement(statement);
        }

        @Override
        public void visitBinaryExpression(GoBinaryExpression expression) {
            super.visitBinaryExpression(expression);
        }

        @Override
        public void visitRelationalExpression(GoRelationalExpression expression) {
            super.visitRelationalExpression(expression);
        }

        @Override
        public void visitPackage(GoPackage aPackage) {
            super.visitPackage(aPackage);
        }

        @Override
        public void visitLiteralFloat(GoLiteral<BigDecimal> literal) {
            super.visitLiteralFloat(literal);
        }

        @Override
        public void visitLiteralChar(GoLiteral<Character> literal) {
            super.visitLiteralChar(literal);
        }

        @Override
        public void visitLiteralImaginary(GoLiteral<GoNumber> imaginary) {
            super.visitLiteralImaginary(imaginary);
        }

        @Override
        public void visitLiteralString(GoLiteral<String> literal) {
            super.visitLiteralString(literal);
        }

        @Override
        public void visitUnaryExpression(GoUnaryExpression expression) {
            super.visitUnaryExpression(expression);
        }

        @Override
        public void visitParenthesisedExprOrType(GoParenthesizedExprOrType exprOrType) {
            super.visitParenthesisedExprOrType(exprOrType);
        }

        @Override
        public void visitMultiplicativeExpression(GoMultiplicativeExpression expression) {
            super.visitMultiplicativeExpression(expression);
        }

        @Override
        public void visitLogicalAndExpression(GoLogicalAndExpression expression) {
            super.visitLogicalAndExpression(expression);
        }

        @Override
        public void visitLogicalOrExpression(GoLogicalOrExpression expression) {
            super.visitLogicalOrExpression(expression);
        }

        @Override
        public void visitAdditiveExpression(GoAdditiveExpression expression) {
            super.visitAdditiveExpression(expression);
        }

        @Override
        public int hashCode() {
            return super.hashCode();
        }

        @Override
        public boolean equals(Object obj) {
            return super.equals(obj);
        }

        @Override
        protected Object clone() throws CloneNotSupportedException {
            return super.clone();
        }

        @Override
        public String toString() {
            return super.toString();
        }

        @Override
        protected void finalize() throws Throwable {
            super.finalize();
        }

        @Override
        public void visitVarDeclaration(GoVarDeclaration declaration) {
            GoLiteralIdentifier[] identifiers = declaration.getIdentifiers();
            GoExpr[] expressions = declaration.getExpressions();
            for (int i = 0; i < expressions.length; i++) {
                GoExpr expression = expressions[i];
                System.out.println(identifiers[i] + " " + expression + ";\n");
            }
        }

        @Override
        public void visitAssignment(GoAssignmentStatement statement) {
            super.visitAssignment(statement);
        }

    }
}
