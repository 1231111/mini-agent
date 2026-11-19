package com.miniagent.agent.planner;

import com.miniagent.agent.tool.ShellCommandLine;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Conservative checks for user-requested code changes and their test commands. */
public final class CodeValidationPolicy {

    private static final Pattern CODE_CHANGE_GOAL = Pattern.compile(
            "(?i)(修复|修正|修补|实现(?:代码|新功能|功能|接口|组件|模块|程序|脚本|软件|类|方法|算法|服务|应用)|"
                    + "新增(?:功能|接口|特性)|开发(?:功能|接口|组件|模块|程序|脚本)|"
                    + "重构(?:代码|模块|函数)?|修改(?:源代码|代码)|"
                    + "(?:更新|修改|更改|变更|update|change|modify|edit)\\s*(?:the\\s+|a\\s+|an\\s+)?[\\w./\\\\-]+\\.(?:java|py|js|jsx|ts|tsx|mjs|cjs|go|rs|cs|cpp|c|h|hpp|sql|sh|ps1|kt|kts|swift|rb|php)\\b|"
                    + "fix(?:\\s+[a-z]|\\s+bug)?|"
                    + "implement|add\\s+(?:a\\s+)?feature|refactor\\s+code)");
    private static final Pattern CODE_ARTIFACT = Pattern.compile(
            "(?i)(\\.(?:java|py|js|jsx|ts|tsx|mjs|cjs|go|rs|cs|cpp|c|h|hpp|sql|sh|ps1|"
                    + "html|css|scss|sass|less|vue|svelte|kt|kts|swift|rb|php)\\b|"
                    + "源代码|源文件|代码|程序|脚本|模块|函数|类)");
    private static final Pattern CODE_CONFIG_ARTIFACT = Pattern.compile(
            "(?i)\\.(?:json|xml|yaml|yml|toml|properties|gradle)\\b");
    private static final Pattern MODULE_HINT = Pattern.compile("(?i)(?:^|/)(mini-agent-[a-z0-9_-]+)(?:/|$)");
    private static final Set<String> TEST_SKIP_FLAGS = Set.of(
            "-dskiptests", "-dskiptests=true", "-dmaven.test.skip", "-dmaven.test.skip=true",
            "-x", "--exclude-task");

    private CodeValidationPolicy() {}

    public static boolean isCodeChangeGoal(String text) {
        return text != null && CODE_CHANGE_GOAL.matcher(text).find();
    }

    public static boolean isCodeArtifact(String text) {
        return text != null && CODE_ARTIFACT.matcher(text).find();
    }

    public static boolean isCodeConfigArtifact(String text) {
        return text != null && CODE_CONFIG_ARTIFACT.matcher(text).find();
    }

    /**
     * True only for a command whose own executable invokes tests. Shell chains,
     * pipes, redirections, wrappers that hide command text, and test-skipping
     * flags are deliberately excluded from acceptance plans.
     */
    public static boolean isTestCommand(String command) {
        return testRunner(command) != null;
    }

    /** Reject a scoped test run when its module selection excludes every changed source module. */
    public static boolean isTestCommandForModules(String command, Set<String> changedModules) {
        return isTestCommandForModules(command, changedModules, false);
    }

    /** Unknown affected files can only be covered by an unscoped whole-project test. */
    public static boolean isTestCommandForModules(String command, Set<String> changedModules,
                                                   boolean unknownAffectedModule) {
        ShellCommandLine.Segment test = testRunner(command);
        if (test == null) return false;
        ProjectSelection selection = projectSelection(test);
        if (unknownAffectedModule && selection.scoped()) return false;
        if (!selection.scoped()) return true;
        if (!selection.supported()) return false;
        Set<String> selected = selection.projects();
        if (selected.isEmpty()) return true; // Explicit all-workspace options cover every changed module.
        if (changedModules == null || changedModules.isEmpty()) return true;
        return changedModules.stream().map(module -> module.toLowerCase(Locale.ROOT))
                .allMatch(selected::contains);
    }

    /** Extract a Maven/Gradle module hint from a workspace path, if present. */
    public static String moduleHint(String path) {
        if (path == null || path.isBlank()) return "";
        String normalized = path.replace('\\', '/');
        java.util.regex.Matcher matcher = MODULE_HINT.matcher(normalized);
        return matcher.find() ? matcher.group(1).toLowerCase(Locale.ROOT) : "";
    }

    private static ShellCommandLine.Segment testRunner(String command) {
        if (command == null || command.isBlank() || ShellCommandLine.unsafeMetacharacter(command) != null) {
            return null;
        }
        List<ShellCommandLine.Segment> segments = ShellCommandLine.segments(command);
        if (segments.isEmpty() || segments.size() > 2) return null;
        ShellCommandLine.Segment test = segments.get(segments.size() - 1);
        if (segments.size() == 2) {
            // Permit the common working-directory prefix, but never a fallback,
            // pipeline, or arbitrary command after the test runner.
            String compact = command.replaceAll("\\s+", " ").trim();
            if (!"cd".equals(segments.getFirst().baseCommand())
                    || !compact.contains("&&") || compact.indexOf("&&") != compact.lastIndexOf("&&")
                    || compact.contains(";") || compact.contains("|") || compact.contains("\n")
                    || compact.contains("\r")) return null;
        }

        List<String> tokens = test.tokens();
        if (tokens.isEmpty()) return null;
        String executable = test.baseCommand().toLowerCase(Locale.ROOT);
        List<String> rawArgs = tokens.subList(1, tokens.size());
        List<String> args = rawArgs.stream()
                .map(token -> token.toLowerCase(Locale.ROOT)).toList();
        if (hasSkipTests(args) || hasNonExecutingTestOption(executable, rawArgs)) return null;

        boolean testCommand = switch (executable) {
            case "mvn", "mvnw" -> containsAny(args, "test", "verify");
            case "gradle", "gradlew" -> containsAny(args, "test", "check", "build");
            case "npm", "pnpm", "yarn" -> packageManagerRunsTests(args);
            case "pytest", "phpunit" -> true;
            case "python", "python3", "py" -> pythonRunsTests(args);
            case "cargo", "go", "dotnet" -> containsFirstCommand(args, "test");
            case "node" -> args.contains("--test");
            default -> false;
        };
        return testCommand ? test : null;
    }

    private static ProjectSelection projectSelection(ShellCommandLine.Segment test) {
        List<String> tokens = test.tokens().stream().map(token -> token.toLowerCase(Locale.ROOT)).toList();
        String executable = test.baseCommand().toLowerCase(Locale.ROOT);
        List<String> args = tokens.size() < 2 ? List.of() : tokens.subList(1, tokens.size());
        java.util.LinkedHashSet<String> projects = new java.util.LinkedHashSet<>();
        boolean scoped = false;
        boolean supported = true;

        for (int i = 0; i < args.size(); i++) {
            String token = args.get(i);
            String value = null;
            if ("mvn".equals(executable) || "mvnw".equals(executable)) {
                if (("-pl".equals(token) || "--projects".equals(token)) && i + 1 < args.size()) {
                    scoped = true;
                    value = args.get(++i);
                } else if (token.startsWith("-pl=") || token.startsWith("--projects=")) {
                    scoped = true;
                    value = token.substring(token.indexOf('=') + 1);
                } else if (("-f".equals(token) || "--file".equals(token)) && i + 1 < args.size()) {
                    scoped = true;
                    String file = args.get(++i).replace('\\', '/');
                    String base = file.substring(file.lastIndexOf('/') + 1);
                    String directory = file.substring(0, Math.max(0, file.lastIndexOf('/')));
                    value = "pom.xml".equals(base) ? directory : base;
                }
            } else if ("cargo".equals(executable)) {
                if (("--workspace".equals(token) || "--all".equals(token))
                        && !args.contains("-p") && !args.contains("--package")) {
                    // Cargo explicitly runs every workspace member.
                    continue;
                }
                if (("-p".equals(token) || "--package".equals(token)) && i + 1 < args.size()) {
                    scoped = true;
                    value = args.get(++i);
                } else if (token.startsWith("--package=")) {
                    scoped = true;
                    value = token.substring("--package=".length());
                }
            } else if (Set.of("npm", "pnpm", "yarn").contains(executable)) {
                if (("--workspaces".equals(token) || "--workspaces=true".equals(token))
                        && !args.contains("--workspace") && !args.contains("-w")) {
                    // This is the plural all-workspaces switch.
                    continue;
                }
                if (("--workspace".equals(token) || "-w".equals(token)) && i + 1 < args.size()) {
                    scoped = true;
                    value = args.get(++i);
                } else if (token.startsWith("--workspace=")) {
                    scoped = true;
                    value = token.substring("--workspace=".length());
                } else if (("--filter".equals(token) || "--filter-prod".equals(token)) && i + 1 < args.size()) {
                    scoped = true;
                    value = args.get(++i);
                } else if (token.startsWith("--filter=")) {
                    scoped = true;
                    value = token.substring("--filter=".length());
                }
            }
            if (value != null) {
                for (String project : value.split(",")) {
                    String normalized = normalizeProject(project);
                    if (!normalized.isBlank()) projects.add(normalized);
                    else supported = false;
                }
            }
        }
        if ("cargo".equals(executable) && !args.contains("--workspace") && !args.contains("--all")
                && !projects.isEmpty()) {
            // Explicit package selection is project-scoped; handled by the mapping above.
        } else if ("cargo".equals(executable)
                && !args.contains("--workspace") && !args.contains("--all")) {
            scoped = true;
            supported = false; // Bare cargo test only runs the default package(s).
        }
        if (Set.of("npm", "pnpm", "yarn").contains(executable)
                && !args.contains("--workspaces") && !args.contains("--workspaces=true")
                && projects.isEmpty()) {
            scoped = true;
            supported = false; // A package script is not proof that every workspace was tested.
        }
        if ("go".equals(executable)) {
            boolean allPackages = args.contains("./...");
            boolean packagePath = args.stream().anyMatch(arg -> arg.startsWith("./") || arg.startsWith("../")
                    || arg.contains("...") || (arg.contains("/") && !arg.startsWith("-")));
            if (!allPackages && packagePath) {
                scoped = true;
                supported = false;
            } else if (!allPackages) {
                scoped = true;
                supported = false; // Bare go test runs only the current package.
            }
        }
        if (hasUnmappedProjectSelector(executable, args)) {
            scoped = true;
            supported = false;
        }
        return new ProjectSelection(scoped, Set.copyOf(projects), supported);
    }

    private static String normalizeProject(String project) {
        String normalized = project.trim().toLowerCase(Locale.ROOT).replace('\\', '/');
        while (normalized.startsWith(":")) normalized = normalized.substring(1);
        if (normalized.endsWith("/pom.xml")) normalized = normalized.substring(0, normalized.length() - 7);
        int slash = normalized.lastIndexOf('/');
        if (slash >= 0) normalized = normalized.substring(slash + 1);
        return normalized;
    }

    private static boolean hasUnmappedProjectSelector(String executable, List<String> args) {
        if ("dotnet".equals(executable)) {
            return args.stream().anyMatch(arg -> arg.endsWith(".csproj") || arg.endsWith(".sln")
                    || "--project".equals(arg) || "--solution".equals(arg));
        }
        if ("gradle".equals(executable) || "gradlew".equals(executable)) {
            return args.stream().anyMatch(arg -> arg.matches(":.+:(?:test|check|build)(?:\\..*)?"));
        }
        if ("go".equals(executable)) {
            if (args.contains("./...")) return false;
            return args.stream().anyMatch(arg -> arg.startsWith("./") || arg.startsWith("../")
                    || arg.contains("...") || (arg.contains("/") && !arg.startsWith("-")));
        }
        return false;
    }

    private record ProjectSelection(boolean scoped, Set<String> projects, boolean supported) {}

    private static boolean hasSkipTests(List<String> args) {
        for (int i = 0; i < args.size(); i++) {
            String arg = args.get(i);
            if (TEST_SKIP_FLAGS.contains(arg)) {
                if ("-x".equals(arg) || "--exclude-task".equals(arg)) {
                    if (i + 1 < args.size() && "test".equals(args.get(i + 1))) return true;
                    if (arg.startsWith("-x=") && "test".equals(arg.substring(3))) return true;
                } else {
                    return true;
                }
            }
            if (arg.startsWith("-dskiptests=") || arg.startsWith("-dmaven.test.skip=")) return true;
            if (arg.equals("-dmaven.test.failure.ignore") || arg.equals("-dmaven.test.failure.ignore=true")
                    || arg.equals("-dfailifnotests=false") || arg.equals("-dsurefire.failifnospecifiedtests=false")) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasNonExecutingTestOption(String executable, List<String> args) {
        List<String> lowerArgs = args.stream().map(arg -> arg.toLowerCase(Locale.ROOT)).toList();
        if (("pytest".equals(executable) || pythonInvokesPytest(args))
                && (containsAny(lowerArgs, "--help", "-h", "--version", "--collect-only", "--co",
                "--fixtures", "--markers", "--setup-plan", "--traceconfig", "--trace-config", "--debug"))) return true;
        if (("pytest".equals(executable) || pythonInvokesPytest(args)) && args.contains("-V")) return true;
        if ("phpunit".equals(executable)
                && containsAny(lowerArgs, "--help", "-h", "--version", "-v", "--list-tests",
                "--list-suites", "--list-groups")) return true;
        if (("python".equals(executable) || "python3".equals(executable) || "py".equals(executable))
                && lowerArgs.size() >= 2 && "-m".equals(lowerArgs.getFirst()) && "unittest".equals(lowerArgs.get(1))
                && containsAny(lowerArgs, "-h", "--help")) return true;
        if ("cargo".equals(executable) && lowerArgs.contains("--no-run")) return true;
        if (Set.of("npm", "pnpm", "yarn").contains(executable)
                && lowerArgs.contains("--if-present")) return true;
        if (lowerArgs.stream().anyMatch(arg -> Set.of("--passwithnotests", "--pass-with-no-tests",
                "--dry-run", "--dryrun", "--no-run").contains(arg))) return true;
        if ("go".equals(executable)) {
            for (int i = 0; i + 1 < lowerArgs.size(); i++) {
                if ("-run".equals(lowerArgs.get(i)) && Set.of("^$", "^\\$", "a^").contains(lowerArgs.get(i + 1))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean pythonInvokesPytest(List<String> args) {
        return args.size() >= 2 && "-m".equals(args.getFirst()) && "pytest".equals(args.get(1));
    }

    private static boolean packageManagerRunsTests(List<String> args) {
        return containsFirstCommand(args, "test")
                || (args.size() > 1 && "run".equals(args.getFirst()) && containsFirstCommand(args.subList(1, args.size()), "test"));
    }

    private static boolean pythonRunsTests(List<String> args) {
        return args.size() >= 2 && "-m".equals(args.getFirst())
                && ("pytest".equals(args.get(1)) || "unittest".equals(args.get(1)));
    }

    private static boolean containsFirstCommand(List<String> args, String expected) {
        for (String arg : args) {
            if (arg.startsWith("-")) continue;
            return expected.equals(arg);
        }
        return false;
    }

    private static boolean containsAny(List<String> args, String... expected) {
        for (String arg : args) {
            for (String candidate : expected) {
                if (candidate.equals(arg)) return true;
            }
        }
        return false;
    }
}
