package agent.memory

import agent.core.MemoryNode
import zio.*
import java.sql.{Connection, DriverManager, PreparedStatement, ResultSet}

// ── MemoryStore service ───────────────────────────────────────────────────

trait MemoryStore:
  def put(node: MemoryNode): Task[Long]
  def get(id: Long): Task[Option[MemoryNode]]
  def children(parentId: Option[Long]): Task[List[MemoryNode]]
  def search(query: String, limit: Int = 10): Task[List[MemoryNode]]
  def path(key: String): Task[List[MemoryNode]]

object MemoryStore:
  val layer: ZLayer[MemoryConfig, Throwable, MemoryStore] =
    ZLayer.fromZIO(
      ZIO.serviceWith[MemoryConfig](cfg => SqliteMemoryStore(cfg.dbPath))
        .flatMap(store => store.init().as(store))
    )

  def put(node: MemoryNode): ZIO[MemoryStore, Throwable, Long] =
    ZIO.serviceWithZIO[MemoryStore](_.put(node))

  def search(query: String, limit: Int = 10): ZIO[MemoryStore, Throwable, List[MemoryNode]] =
    ZIO.serviceWithZIO[MemoryStore](_.search(query, limit))

  def path(key: String): ZIO[MemoryStore, Throwable, List[MemoryNode]] =
    ZIO.serviceWithZIO[MemoryStore](_.path(key))

case class MemoryConfig(dbPath: String)

object MemoryConfig:
  val fromEnv: ZLayer[Any, Nothing, MemoryConfig] =
    ZLayer.fromZIO(
      zio.System.envOrElse("AGENT_MEMORY_DB", s"${java.lang.System.getProperty("user.home")}/.ai-agent/memory.db")
        .map(MemoryConfig(_))
        .orDie
    )

// ── SQLite 实现 ───────────────────────────────────────────────────────────

private class SqliteMemoryStore(dbPath: String) extends MemoryStore:

  Class.forName("org.sqlite.JDBC")

  // 统一连接管理：获取连接，执行，释放
  private def withConn[A](f: Connection => A): Task[A] =
    ZIO.acquireReleaseWith(
      ZIO.attemptBlocking {
        val dir = java.io.File(dbPath).getParentFile
        if dir != null then dir.mkdirs()
        DriverManager.getConnection(s"jdbc:sqlite:$dbPath").nn
      }
    )(conn => ZIO.succeed(conn.close()))(conn => ZIO.attemptBlocking(f(conn)))

  // ResultSet 遍历为 List
  private def toList(rs: ResultSet): List[MemoryNode] =
    val buf = scala.collection.mutable.ListBuffer.empty[MemoryNode]
    while rs.next() do buf += rowToNode(rs)
    buf.toList

  def init(): Task[Unit] = withConn { conn =>
    conn.createStatement().nn.execute(
      """CREATE TABLE IF NOT EXISTS memory (
        |  id         INTEGER PRIMARY KEY AUTOINCREMENT,
        |  parent_id  INTEGER,
        |  key        TEXT NOT NULL,
        |  summary    TEXT NOT NULL,
        |  content    TEXT NOT NULL,
        |  created_at INTEGER NOT NULL,
        |  FOREIGN KEY(parent_id) REFERENCES memory(id)
        |)""".stripMargin
    )
    conn.createStatement().nn.execute("CREATE INDEX IF NOT EXISTS idx_key    ON memory(key)")
    conn.createStatement().nn.execute("CREATE INDEX IF NOT EXISTS idx_parent ON memory(parent_id)")
  }

  def put(node: MemoryNode): Task[Long] = withConn { conn =>
    val ps = conn.prepareStatement(
      "INSERT INTO memory(parent_id, key, summary, content, created_at) VALUES (?,?,?,?,?)",
      java.sql.Statement.RETURN_GENERATED_KEYS
    ).nn
    node.parentId.fold(ps.setNull(1, java.sql.Types.INTEGER))(ps.setLong(1, _))
    ps.setString(2, node.key)
    ps.setString(3, node.summary)
    ps.setString(4, node.content)
    ps.setLong(5, node.createdAt)
    ps.executeUpdate()
    val keys = ps.getGeneratedKeys.nn
    if keys.next() then keys.getLong(1) else -1L
  }

  def get(id: Long): Task[Option[MemoryNode]] = withConn { conn =>
    val ps = conn.prepareStatement("SELECT * FROM memory WHERE id = ?").nn
    ps.setLong(1, id)
    val rs = ps.executeQuery().nn
    if rs.next() then Some(rowToNode(rs)) else None
  }

  def children(parentId: Option[Long]): Task[List[MemoryNode]] = withConn { conn =>
    val ps = parentId match
      case None =>
        conn.prepareStatement("SELECT * FROM memory WHERE parent_id IS NULL ORDER BY key").nn
      case Some(pid) =>
        val s = conn.prepareStatement("SELECT * FROM memory WHERE parent_id = ? ORDER BY key").nn
        s.setLong(1, pid)
        s
    toList(ps.executeQuery().nn)
  }

  def search(query: String, limit: Int): Task[List[MemoryNode]] = withConn { conn =>
    val like = s"%$query%"
    val ps   = conn.prepareStatement(
      "SELECT * FROM memory WHERE key LIKE ? OR summary LIKE ? OR content LIKE ? ORDER BY created_at DESC LIMIT ?"
    ).nn
    ps.setString(1, like); ps.setString(2, like); ps.setString(3, like); ps.setInt(4, limit)
    toList(ps.executeQuery().nn)
  }

  // 单次 IN 查询取得整条路径链，替代 N 次独立查询
  def path(key: String): Task[List[MemoryNode]] =
    val segments = key.split("/").scanLeft("")((acc, s) => if acc.isEmpty then s else s"$acc/$s").drop(1).toList
    if segments.isEmpty then ZIO.succeed(Nil)
    else withConn { conn =>
      val placeholders = segments.map(_ => "?").mkString(",")
      val ps = conn.prepareStatement(
        s"SELECT * FROM memory WHERE key IN ($placeholders) ORDER BY length(key), created_at DESC"
      ).nn
      segments.zipWithIndex.foreach { case (k, i) => ps.setString(i + 1, k) }
      // 每个 key 取最新一条（按 key 分组取第一个）
      toList(ps.executeQuery().nn)
        .groupBy(_.key)
        .map { case (_, nodes) => nodes.head }
        .toList
        .sortBy(_.key.length)
    }

  private def rowToNode(rs: ResultSet): MemoryNode =
    val parentIdRaw = rs.getLong("parent_id")
    MemoryNode(
      id        = rs.getLong("id"),
      parentId  = if rs.wasNull() then None else Some(parentIdRaw),
      key       = rs.getString("key").nn,
      summary   = rs.getString("summary").nn,
      content   = rs.getString("content").nn,
      createdAt = rs.getLong("created_at"),
    )
