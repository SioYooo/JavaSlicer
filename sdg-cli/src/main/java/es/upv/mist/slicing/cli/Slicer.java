package es.upv.mist.slicing.cli;

import com.github.javaparser.Problem;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.comments.BlockComment;
import com.github.javaparser.ast.nodeTypes.NodeWithName;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import es.upv.mist.slicing.graphs.augmented.ASDG;
import es.upv.mist.slicing.graphs.augmented.PSDG;
import es.upv.mist.slicing.graphs.exceptionsensitive.ESSDG;
import es.upv.mist.slicing.graphs.jsysdg.JSysDG;
import es.upv.mist.slicing.graphs.sdg.SDG;
import es.upv.mist.slicing.slicing.FileLineSlicingCriterion;
import es.upv.mist.slicing.slicing.Slice;
import es.upv.mist.slicing.slicing.SlicingCriterion;
import es.upv.mist.slicing.utils.NodeHashSet;
import es.upv.mist.slicing.utils.StaticTypeSolver;
import org.apache.commons.cli.*;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public class Slicer {
    protected static final String HELP_HEADER = "Java SDG Slicer: extract a slice from a Java program. At least" +
            " the \"-c\" flag must be used to specify the slicing criterion.";

    protected static final Pattern SC_PATTERN;
    protected static final File DEFAULT_OUTPUT_DIR = new File("./slice/");
    protected static final Options OPTIONS = new Options();

    static {
        String fileRe = "(?<file>[^#]+\\.java)";
        String lineRe = "(?<line>[1-9]\\d*)";
        String varsRe = "(?<var>[a-zA-Z_]\\w*(?:,[a-zA-Z_]\\w*)*)";
        SC_PATTERN = Pattern.compile(fileRe + "#" + lineRe + "(?::" + varsRe + ")?");
    }

    static {
        OPTIONS.addOption(Option
                .builder("f").longOpt("file")
                .hasArg().argName("CriterionFile.java").type(File.class)
                .desc("The file that contains the slicing criterion.")
                .build());
        OPTIONS.addOption(Option
                .builder("l").longOpt("line")
                .hasArg().argName("line-number").type(Number.class)
                .desc("The line that contains the statement of the slicing criterion.")
                .build());
        OPTIONS.addOption(Option
                .builder("v").longOpt("var")
                .hasArgs().argName("variable-name")
                .desc("The name of the variable of the slicing criterion. Not setting this option is" +
                        " equivalent to selecting all the code in the given line number.")
                .build());
        OPTIONS.addOption(Option
                .builder("c").longOpt("criterion")
                .hasArg().argName("file#line[:var]")
                .desc("The slicing criterion, in the format \"file#line:var\". The variable is optional."+
                        " This option may be replaced by \"-f\", \"-l\" and \"-v\"." +
                        " If this argument is set, it will override the individual ones.")
                .build());
        OPTIONS.addOption(Option
                .builder("i").longOpt("include")
                .hasArgs().argName("directory[,directory,...]").valueSeparator(',')
                .desc("Includes the directories listed in the search for methods called from the slicing criterion " +
                        "(directly or transitively). Methods that are not included here or part of the JRE, including" +
                        " third party libraries will not be analyzed, resulting in less precise slicing.")
                .build());
        OPTIONS.addOption(Option
                .builder("o").longOpt("output")
                .hasArg().argName("output-dir")
                .desc("The directory where the sliced source code should be placed. By default, it is placed at " +
                        DEFAULT_OUTPUT_DIR)
                .build());
        OPTIONS.addOption(Option
                .builder("t").longOpt("type")
                .hasArg().argName("graph-type")
                .desc("The type of graph to be built. Available options are SDG, ASDG, PSDG, ESSDG, JSysDG.")
                .build());
        OPTIONS.addOption(Option
                .builder("h").longOpt("help")
                .desc("Shows this text")
                .build());
        OPTIONS.addOption(Option
                .builder("a").longOpt("all")
                .desc("Slice all variables in the project and export to JSON")
                .build());
        OPTIONS.addOption(Option
                .builder("p").longOpt("project")
                .hasArg().argName("project-name")
                .desc("The name of the project (used for EID generation in -a mode)")
                .build());
    }

    private final Set<File> dirIncludeSet = new HashSet<>();
    private File outputDir = DEFAULT_OUTPUT_DIR;
    private File scFile;
    private int scLine;
    private String scVar;
    private final CommandLine cliOpts;

    public Slicer(String... cliArgs) throws ParseException {
        cliOpts = new DefaultParser().parse(OPTIONS, cliArgs);
        if (cliOpts.hasOption('h'))
            printHelp();
        
        if (cliOpts.hasOption('a')) {
            // In 'all' mode, we don't need specific criterion arguments
            if (cliOpts.hasOption('c') || (cliOpts.hasOption('f') && cliOpts.hasOption('l'))) {
                System.out.println("Warning: Slicing criterion arguments ignored in 'all' mode.");
            }
        } else {
            if (cliOpts.hasOption('c')) {
                Matcher matcher = SC_PATTERN.matcher(cliOpts.getOptionValue("criterion"));
                if (!matcher.matches())
                    throw new ParseException("Invalid format for slicing criterion, see --help for more details");
                setScFile(matcher.group("file"));
                setScLine(Integer.parseInt(matcher.group("line")));
                String var = matcher.group("var");
                if (var != null)
                    setScVar(var);
            } else if (cliOpts.hasOption('f') && cliOpts.hasOption('l')) {
                setScFile(cliOpts.getOptionValue('f'));
                setScLine(((Number) cliOpts.getParsedOptionValue("l")).intValue());
                if (cliOpts.hasOption('v'))
                    setScVar(cliOpts.getOptionValue('v'));
            } else {
                throw new ParseException("Slicing criterion not specified: either use \"-c\" or \"-f\" and \"-l\".");
            }
        }

        if (cliOpts.hasOption('o'))
            outputDir = new File(cliOpts.getOptionValue("o"));

        if (cliOpts.hasOption('i')) {
            for (String str : cliOpts.getOptionValues('i')) {
                File dir = new File(str);
                if (!dir.isDirectory())
                    throw new ParseException("One of the include directories is not a directory or isn't accesible: " + str);
                dirIncludeSet.add(dir);
            }
        }
    }

    private void setScFile(String fileName) throws ParseException {
        File file = new File(fileName);
        if (!(file.exists() && file.isFile()))
            throw new ParseException("Slicing criterion file is not an existing file.");
        scFile = file;
    }

    private void setScLine(int line) throws ParseException {
        if (line <= 0)
            throw new ParseException("The line of the slicing criterion must be strictly greater than zero.");
        scLine = line;
    }

    private void setScVar(String scVar) {
        this.scVar = scVar;
    }

    public Set<File> getDirIncludeSet() {
        return Collections.unmodifiableSet(dirIncludeSet);
    }

    public File getOutputDir() {
        return outputDir;
    }

    public File getScFile() {
        return scFile;
    }

    public int getScLine() {
        return scLine;
    }

    public String getScVar() {
        return scVar;
    }

    public void slice() throws ParseException {
        // Configure JavaParser
        StaticJavaParser.getConfiguration().setAttributeComments(false);
        Logger.getLogger(Logger.GLOBAL_LOGGER_NAME).log(Level.INFO, "Configuring JavaParser");
        StaticTypeSolver.addTypeSolverJRE();
        for (File directory : dirIncludeSet)
            StaticTypeSolver.addTypeSolver(new JavaParserTypeSolver(directory));

        // Build the SDG
        Logger.getLogger(Logger.GLOBAL_LOGGER_NAME).log(Level.INFO, "Parsing files");
        Set<CompilationUnit> units = new NodeHashSet<>();
        List<Problem> problems = new LinkedList<>();
        boolean scFileFound = false;
        for (File file : (Iterable<File>) findAllJavaFiles(dirIncludeSet)::iterator)
            scFileFound |= parse(file, units, problems);
        
        // In 'all' mode, we might not have scFile, but we need to parse all files in include dirs
        if (!cliOpts.hasOption('a') && !scFileFound)
            parse(scFile, units, problems);
            
        if (!problems.isEmpty()) {
            for (Problem p : problems)
                System.out.println(" * " + p.getVerboseMessage());
            throw new ParseException("Some problems were found while parsing files or folders");
        }

        SDG sdg;
        switch (cliOpts.getOptionValue("type", "JSysDG")) {
            case "SDG":    sdg = new SDG();    break;
            case "ASDG":   sdg = new ASDG();   break;
            case "PSDG":   sdg = new PSDG();   break;
            case "ESSDG":  sdg = new ESSDG();  break;
            case "JSysDG": sdg = new JSysDG(); break;
            default:
                throw new IllegalArgumentException("Unknown type of graph. Available graphs are SDG, ASDG, PSDG, ESSDG, JSysDG.");
        }
        Logger.getLogger(Logger.GLOBAL_LOGGER_NAME).log(Level.INFO, "Building the SDG");
        sdg.build(new NodeList<>(units));

        if (cliOpts.hasOption('a')) {
            sliceAll(sdg, units);
            return;
        }

        // Slice the SDG
        Logger.getLogger(Logger.GLOBAL_LOGGER_NAME).log(Level.INFO, "Searching for criterion and slicing");
        SlicingCriterion sc = new FileLineSlicingCriterion(scFile, scLine, scVar);
        Slice slice = sdg.slice(sc);

        // Convert the slice to code and output the result to `outputDir`
        Logger.getLogger(Logger.GLOBAL_LOGGER_NAME).log(Level.INFO, "Printing slice to files");
        for (CompilationUnit cu : slice.toAst()) {
            if (cu.getStorage().isEmpty())
                throw new IllegalStateException("A synthetic CompilationUnit was discovered, with no file associated to it.");
            Logger.getLogger(Logger.GLOBAL_LOGGER_NAME).log(Level.INFO, "Printing slice for " + cu.getStorage().get().getFileName());
            String packagePath = cu.getPackageDeclaration().map(NodeWithName::getNameAsString).orElse("").replace(".", "/");
            File packageDir = new File(outputDir, packagePath);
            packageDir.mkdirs();
            File javaFile = new File(packageDir, cu.getStorage().get().getFileName());
            try (PrintWriter pw = new PrintWriter(javaFile)) {
                pw.print(new BlockComment(getDisclaimer(cu.getStorage().get())));
                pw.print(cu);
            } catch (FileNotFoundException e) {
                System.err.println("Could not write file " + javaFile);
            }
        }
    }

    private void sliceAll(SDG sdg, Set<CompilationUnit> units) {
        Logger.getLogger(Logger.GLOBAL_LOGGER_NAME).log(Level.INFO, "Slicing all variables...");
        List<Map<String, Object>> results = new ArrayList<>();
        String projectName = cliOpts.getOptionValue("p", "unknown_project");

        for (CompilationUnit cu : units) {
            if (cu.getStorage().isEmpty()) continue;
            String filePath = cu.getStorage().get().getPath().toString();
            String fileName = cu.getStorage().get().getFileName();
            
            // Try to make path relative to project root if possible, or just use filename for hash stability?
            // User said "project name + file + variable". 
            // We'll use the file name as it appears in the storage (absolute path usually).
            // To be safe across environments, maybe we should try to relativize against include dirs?
            // But for now, let's use the full path or just filename if that's what "file" implies.
            // Given "project name + file", usually implies relative path within project.
            // Let's try to find the relative path from the include directories.
            
            String relativePath = filePath;
            for (File includeDir : dirIncludeSet) {
                if (filePath.startsWith(includeDir.getAbsolutePath())) {
                    relativePath = filePath.substring(includeDir.getAbsolutePath().length());
                    if (relativePath.startsWith(File.separator)) {
                        relativePath = relativePath.substring(1);
                    }
                    break;
                }
            }

            List<CallableDeclaration> callables = cu.findAll(CallableDeclaration.class);
            for (CallableDeclaration callable : callables) {
                String functionName = callable.getNameAsString();
                String className = "";
                if (callable.getParentNode().isPresent() && callable.getParentNode().get() instanceof com.github.javaparser.ast.body.TypeDeclaration) {
                    className = ((com.github.javaparser.ast.body.TypeDeclaration<?>) callable.getParentNode().get()).getNameAsString();
                }
                
                String functionCode = callable.toString();
                List<Map<String, Object>> slices = new ArrayList<>();

                // Find variables
                List<VariableDeclarator> vars = callable.findAll(VariableDeclarator.class);
                // Also parameters
                List<Parameter> params = callable.findAll(Parameter.class);

                List<com.github.javaparser.ast.Node> allVars = new ArrayList<>();
                allVars.addAll(vars);
                allVars.addAll(params);

                for (com.github.javaparser.ast.Node varNode : allVars) {
                    String varName = "";
                    int line = -1;
                    if (varNode instanceof VariableDeclarator) {
                        varName = ((VariableDeclarator) varNode).getNameAsString();
                        line = ((VariableDeclarator) varNode).getBegin().map(p -> p.line).orElse(-1);
                    } else if (varNode instanceof Parameter) {
                        varName = ((Parameter) varNode).getNameAsString();
                        line = ((Parameter) varNode).getBegin().map(p -> p.line).orElse(-1);
                    }

                    if (line == -1) continue;

                    try {
                        SlicingCriterion sc = new FileLineSlicingCriterion(new File(filePath), line, varName);
                        Slice slice = sdg.slice(sc);
                        
                        // Process slice
                        List<Map<String, Object>> nodesData = new ArrayList<>();
                        
                        // Get all statements in the function to map y_bwd
                        List<Statement> statements = callable.findAll(Statement.class);
                        
                        // Optimization: Build a set of AST nodes in the slice
                        Set<com.github.javaparser.ast.Node> slicedAstNodes = new HashSet<>();
                        for (es.upv.mist.slicing.nodes.GraphNode<?> gn : slice.getGraphNodes()) {
                            if (gn.getAstNode() != null) {
                                slicedAstNodes.add(gn.getAstNode());
                            }
                        }
                        
                        for (Statement stmt : statements) {
                            if (!stmt.getBegin().isPresent()) continue;
                            
                            boolean inSlice = slicedAstNodes.contains(stmt);
                            
                            Map<String, Object> nodeInfo = new HashMap<>();
                            nodeInfo.put("line", stmt.getBegin().get().line);
                            nodeInfo.put("code", stmt.toString());
                            nodeInfo.put("y_fwd", 0); // Forward slicing not supported yet
                            nodeInfo.put("y_bwd", inSlice ? 1 : 0);
                            nodeInfo.put("id", String.valueOf(stmt.hashCode())); 
                            
                            nodesData.add(nodeInfo);
                        }

                        Map<String, Object> sliceData = new HashMap<>();
                        Map<String, Object> criterion = new HashMap<>();
                        criterion.put("variable", varName);
                        criterion.put("line", line);
                        sliceData.put("slice_criterion", criterion);
                        sliceData.put("nodes", nodesData);
                        sliceData.put("edges", new ArrayList<>()); // Empty for now

                        slices.add(sliceData);

                    } catch (Exception e) {
                        System.err.println("Error slicing variable " + varName + " at line " + line + ": " + e.getMessage());
                    }
                }

                if (!slices.isEmpty()) {
                    // Generate unique EID
                    // Hash: project + file + variable (we use function name too to be more precise?)
                    // User said "project name + file + variable".
                    // But we are grouping by function in the output structure.
                    // The 'eid' is per function entry in the JSON list.
                    // So it should be hash of project + file + function.
                    
                    String uniqueString = projectName + "|" + relativePath + "|" + functionName;
                    String eid = generateHash(uniqueString);
                    
                    Map<String, Object> funcResult = new HashMap<>();
                    funcResult.put("eid", eid);
                    funcResult.put("function_name", functionName);
                    funcResult.put("class_name", className);
                    funcResult.put("file_name", fileName);
                    funcResult.put("language", "java");
                    funcResult.put("function_code", functionCode);
                    funcResult.put("slices", slices);
                    results.add(funcResult);
                }
            }
        }

        // Export to JSON
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        File jsonFile = new File(outputDir, "slicing_result.json");
        outputDir.mkdirs();
        try (FileWriter writer = new FileWriter(jsonFile)) {
            gson.toJson(results, writer);
            Logger.getLogger(Logger.GLOBAL_LOGGER_NAME).log(Level.INFO, "Exported JSON to " + jsonFile.getAbsolutePath());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private String generateHash(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] encodedhash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder(2 * encodedhash.length);
            for (int i = 0; i < encodedhash.length; i++) {
                String hex = Integer.toHexString(0xff & encodedhash[i]);
                if(hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    private boolean parse(File file, Set<CompilationUnit> units, List<Problem> problems) {
        try {
            units.add(StaticJavaParser.parse(file));
        } catch (FileNotFoundException e) {
            problems.add(new Problem(e.getLocalizedMessage(), null, e));
        }
        return scFile != null && Objects.equals(file.getAbsoluteFile(), scFile.getAbsoluteFile());
    }

    protected Stream<File> findAllJavaFiles(Collection<File> files) {
        Stream.Builder<File> builder = Stream.builder();
        for (File file : files)
            if (file.isDirectory())
                findAllJavaFiles(file, builder);
            else
                builder.accept(file);
        return builder.build();
    }

    protected Stream<File> findAllJavaFiles(File directory) {
        Stream.Builder<File> builder = Stream.builder();
        findAllJavaFiles(directory, builder);
        return builder.build();
    }

    protected void findAllJavaFiles(File directory, Stream.Builder<File> builder) {
        File[] files = directory.listFiles();
        if (files == null)
            return;
        for (File f : files) {
            if (f.isDirectory())
                findAllJavaFiles(f, builder);
            else if (f.getName().endsWith(".java"))
                builder.accept(f);
        }
    }

    protected String getDisclaimer(CompilationUnit.Storage s) {
        return String.format("\n\tThis file was automatically generated as part of a slice with criterion" +
                        "\n\tfile: %s, line: %d, variable: %s\n\tOriginal file: %s\n",
                scFile, scLine, scVar, s.getPath());
    }

    protected void printHelp() {
        HelpFormatter formatter = new HelpFormatter();
        formatter.setWidth(120);
        formatter.printHelp("java -jar sdg-cli.jar", HELP_HEADER, OPTIONS, "", true);
        System.exit(0);
    }

    public static void main(String... args) {
        try {
            new Slicer(args).slice();
        } catch (ParseException e) {
            System.err.println("Error parsing the arguments!\n" + e.getMessage());
        }
    }
}
