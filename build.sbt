ThisBuild / scalaVersion := "3.4.2"
ThisBuild / organization := "agent"
ThisBuild / version := "0.1.0-SNAPSHOT"

val zioVersion          = "2.1.6"
val zioJsonVersion      = "0.7.1"
val sttpVersion         = "3.9.7"
val sqliteVersion       = "3.46.0.0"
val fansiVersion        = "0.4.0"
val jlineVersion        = "3.26.3"

lazy val commonSettings = Seq(
  scalacOptions ++= Seq("-deprecation", "-feature", "-Xcheck-macros"),
  libraryDependencies ++= Seq(
    "dev.zio" %% "zio"          % zioVersion,
    "dev.zio" %% "zio-streams"  % zioVersion,
    "dev.zio" %% "zio-json"     % zioJsonVersion,
    "dev.zio" %% "zio-test"     % zioVersion % Test,
    "dev.zio" %% "zio-test-sbt" % zioVersion % Test,
  ),
  testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"),
)

// ── core: 数据模型 + LLM 客户端 ──────────────────────────────────────────
lazy val core = project
  .settings(commonSettings)
  .settings(
    name := "core",
    libraryDependencies ++= Seq(
      "com.softwaremill.sttp.client3" %% "zio"         % sttpVersion,
      "com.softwaremill.sttp.client3" %% "zio-json"    % sttpVersion,
    ),
  )

// ── memory: SQLite 树形记忆 ───────────────────────────────────────────────
lazy val memory = project
  .dependsOn(core)
  .settings(commonSettings)
  .settings(
    name := "memory",
    libraryDependencies ++= Seq(
      "org.xerial" % "sqlite-jdbc" % sqliteVersion,
    ),
  )

// ── tools: 内置工具 ───────────────────────────────────────────────────────
lazy val tools = project
  .dependsOn(core)
  .settings(commonSettings)
  .settings(name := "tools")

// ── plugins: 插件系统 ─────────────────────────────────────────────────────
lazy val plugins = project
  .dependsOn(core)
  .settings(commonSettings)
  .settings(
    name := "plugins",
    libraryDependencies ++= Seq(
      "com.softwaremill.sttp.client3" %% "zio" % sttpVersion,
    ),
  )

// ── planner: Plan + 动态 TaskTree + Verifier ─────────────────────────────
lazy val planner = project
  .dependsOn(core, tools, memory)
  .settings(commonSettings)
  .settings(name := "planner")

// ── cli: 入口 ─────────────────────────────────────────────────────────────
lazy val cli = project
  .dependsOn(core, planner, memory, tools, plugins)
  .enablePlugins(NativeImagePlugin)
  .settings(commonSettings)
  .settings(
    name := "cli",
    libraryDependencies ++= Seq(
      "com.lihaoyi"     %% "fansi"          % fansiVersion,
      "org.jline"        % "jline"          % jlineVersion,
      "org.jline"        % "jline-terminal" % jlineVersion,
      "org.jline"        % "jline-reader"   % jlineVersion,
    ),
    // sbt-assembly fat jar
    assembly / mainClass := Some("agent.cli.Main"),
    assembly / assemblyJarName := "ai-agent.jar",
    assembly / assemblyMergeStrategy := {
      case PathList("META-INF", xs @ _*) => MergeStrategy.discard
      case "reference.conf"              => MergeStrategy.concat
      case x                             => MergeStrategy.first
    },
    Compile / mainClass := Some("agent.cli.Main"),
    // 用环境变量 JAVA_HOME 或 GRAALVM_HOME 指定本地 GraalVM，避免插件自动下载
    nativeImageGraalHome := {
      val fromEnv = sys.env.get("GRAALVM_HOME").orElse(sys.env.get("JAVA_HOME")).filter(_.nonEmpty)
      fromEnv.map(p => java.nio.file.Paths.get(p)).getOrElse {
        val bundled = (ThisBuild / baseDirectory).value /
          ".graalvm" / "graalvm-community-openjdk-25.0.2+10.1" / "Contents" / "Home"
        bundled.toPath
      }
    },
    // native-image 输出路径和选项
    nativeImageOutput := (ThisBuild / baseDirectory).value / "dist" / "ai-agent",
    nativeImageOptions ++= Seq(
      "--no-fallback",
      "--enable-native-access=ALL-UNNAMED",
      "-H:+UnlockExperimentalVMOptions",
      "-H:+ReportExceptionStackTraces",
      "-H:ReflectionConfigurationFiles=" +
        ((ThisBuild / baseDirectory).value / "native-image-configs" / "reflect-config.json").getPath,
      "-H:ResourceConfigurationFiles=" +
        ((ThisBuild / baseDirectory).value / "native-image-configs" / "resource-config.json").getPath,
      "-H:JNIConfigurationFiles=" +
        ((ThisBuild / baseDirectory).value / "native-image-configs" / "jni-config.json").getPath,
      // Scala stdlib + ZIO 依赖库在编译期初始化（native-image 标准做法）
      "--initialize-at-build-time=" + Seq(
        "scala",
        "fansi",
        "sourcecode",
        "izumi",
        "org.sqlite.util.ProcessRunner",
      ).mkString(","),
      "--initialize-at-run-time=io.netty,sttp.client3.httpclient",
    ),
  )

lazy val root = project
  .in(file("."))
  .aggregate(core, memory, tools, plugins, planner, cli)
  .settings(name := "ai-agent")
