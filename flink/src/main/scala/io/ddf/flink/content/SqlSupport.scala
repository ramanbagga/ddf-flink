package io.ddf.flink.content

import io.ddf.etl.Types.JoinType
import io.ddf.flink.content.SqlSupport.{Select, TableDdlParser}
import org.apache.flink.api.common.typeinfo.{BasicTypeInfo, TypeInformation}
import org.apache.flink.api.table.expressions._
import org.apache.flink.api.table.parser.ExpressionParser
import org.apache.flink.api.table.parser.ExpressionParser._

object SqlSupport {

  abstract class Function

  case class Create(tableName: String, columns: List[(String, String)]) extends Function

  case class Load(tableName: String,
                  delimiter: Char,
                  url: String,
                  nullValue: String,
                  emptyValue: String,
                  useDefaults: Boolean) extends Function

  case class Select(project: Projection,
                    relations: Array[Relation],
                    where: Option[Where],
                    group: Option[GroupBy],
                    order: Option[OrderBy],
                    limit: Int) extends Function {

    override def toString: String =
      s"Select($project)From(${relations.mkString(",")})Where($where)GroupBy($group)OrderBy($order)Limit($limit)"

    def validate(): Unit = {
      if (!project.isStar) {
        val expressions = project.asInstanceOf[ExprProjection].expressions
        val exprMap = expressions.map(e => (e.name, e)).toMap
        order.foreach { o =>
          o.columns.foreach { os =>
            if (!exprMap.contains(os._1)) {
              throw new IllegalArgumentException("Projection does not contain order by column(" + os._1 + ")")
            }
          }
        }
        group.foreach { g =>
          g.expression.foreach { os =>
            if (!expressions.contains(os)) {
              throw new IllegalArgumentException("Projection does not contain order by column(" + os + ")")
            }
          }
        }
      }
    }
  }

  case class Star() extends Expression {
    val lit = Literal(0)

    override def typeInfo: TypeInformation[_] = lit.typeInfo

    override def children: Seq[Expression] = lit.children
  }

  abstract sealed class Projection(starProjection: Boolean) {
    def isStar: Boolean = starProjection
  }

  case class StarProjection() extends Projection(true) {
    override def toString: String = "*"
  }

  case class ExprProjection(expressions: Expression*) extends Projection(false) {
    override def toString: String = expressions.mkString(",")
  }

  abstract class Relation(tableName: String) {
    def getTableName: String = tableName

    override def toString: String = tableName
  }

  case class SimpleRelation(tableName: String, alias: String) extends Relation(tableName)

  case class JoinRelation(tableName: String,
                          withTableName: String,
                          joinCondition: JoinCondition,
                          joinType: JoinType) extends Relation(tableName) {
    override def toString: String = s"$joinType join $tableName with $withTableName ON ($joinCondition)"
  }

  case class JoinCondition(left: Seq[String], right: Seq[String])

  case class Where(expression: Expression)

  case class GroupBy(expression: Expression*)

  case class OrderBy(columns: (String, Boolean)*)

  class BinaryExpr(expression: Expression, and: Boolean) {
    def expr: Expression = expression

    def isAnd: Boolean = and
  }

  case class AndExpr(expression: Expression) extends BinaryExpr(expression, true)

  case class OrExpr(expression: Expression) extends BinaryExpr(expression, false)


  trait ExprParser extends ExpressionParser.PackratParser[Function] {


    lazy val create: ExpressionParser.PackratParser[Create] =
      ((CREATE ~> TABLE) ~> tableName) ~ columnsWithTypes ^^ { case name ~ contents => Create(name.tableName, contents) }

    lazy val load: ExpressionParser.PackratParser[Load] =
      (LOAD ~> quotedStr) ~
        (DELIMITED ~> BY ~> quotedStr).? ~
        (WITH ~> NULL ~> quotedStr).? ~
        (WITH ~> EMPTY ~> quotedStr).? ~
        (NO ~> DEFAULTS).? ~
        (INTO ~> tableName) ^^ {

        case url ~ dl ~ nullVal ~ emptyVal ~ noDef ~ name =>
          Load(name.tableName, dl.getOrElse(",").toCharArray()(0), url,
            nullValue = nullVal.orNull,
            emptyValue = emptyVal.orNull,
            noDef.map {
              case s: String => false
              case _ => true
            }.getOrElse(true))
      }

    lazy val select: Parser[Select] =
      (SELECT ~> projection) ~
        (FROM ~> relations) ~
        (WHERE ~> predicate).? ~
        (GROUP ~> BY ~> grouping).? ~
        (ORDER ~> BY ~> orderColumns).? ~
        (LIMIT ~> wholeNumber).? ^^ {

        case p ~ t ~ w ~ g ~ o ~ l =>

          val iWhere = w.map(expression => Where(expression))
          val iGroup = g.map(expressions => GroupBy(expressions.toArray: _*))
          val iOrder = o.map(orderByExpressions => OrderBy(orderByExpressions: _*))

          val limit = l.map(s => s.toInt).getOrElse(-1)

          Select(p, t.toArray, iWhere, iGroup, iOrder, limit)
      }

    protected lazy val projection: ExpressionParser.PackratParser[Projection] =
      "*" ^^ { s => StarProjection() } | repsep(functionWithAlias | expression, ",") ^^ { e => ExprProjection(e: _*) }

    protected lazy val grouping: ExpressionParser.PackratParser[List[Expression]] =
      repsep(function | expression, ",") ^^ { case e => e }


    protected lazy val relations: ExpressionParser.PackratParser[List[Relation]] = repsep(relation, ",") ^^ {
      List() ++ _
    }

    protected lazy val relation: Parser[Relation] =
      tableName ~ (joinType.? ~ JOIN ~ tableName ~ ON ~ columnEqualities).? ^^ {
        case lhs ~ rhs =>
          rhs match {
            case Some(jt ~ j ~ rt ~ o ~ ce) =>
              val (left, right) = ce.unzip
              JoinRelation(lhs.getTableName, rt.getTableName, JoinCondition(left, right), jt.getOrElse(JoinType.INNER))
            case None => lhs
          }

      }

    protected lazy val columnEqualities: ExpressionParser.PackratParser[List[(String, String)]] = "(" ~> repsep(columnEquality, AND) <~ ")" ^^ {
      List() ++ _
    }

    protected lazy val columnEquality: Parser[(String, String)] = ident ~ EQ ~ ident ^^ { case left ~ e ~ right => (left, right) }

    protected lazy val joinType: Parser[JoinType] =
      (INNER ^^^ JoinType.INNER
        | LEFT ~ SEMI ^^^ JoinType.LEFTSEMI
        | LEFT ~ OUTER.? ^^^ JoinType.LEFT
        | RIGHT ~ OUTER.? ^^^ JoinType.RIGHT
        | FULL ~ OUTER.? ^^^ JoinType.FULL
        )

    protected lazy val tableName: ExpressionParser.PackratParser[SimpleRelation] =
      ident ~ (AS ~> ident).? ^^ {
        case t ~ als => SimpleRelation(t, als.getOrElse(t))
      }

    protected lazy val columnsWithTypes: ExpressionParser.PackratParser[List[(String, String)]] = "(" ~> repsep(columnWithType, ",") <~ ")" ^^ {
      List() ++ _
    }

    protected lazy val orderColumns: ExpressionParser.PackratParser[List[(String, Boolean)]] = repsep(columnWithOrder, ",") ^^ {
      List() ++ _
    }

    protected lazy val columnWithOrder: ExpressionParser.PackratParser[(String, Boolean)] =
      ident ~ ASC ^^ { case s ~ a => (s, true) } |
        ident ~ DESC ^^ { case s ~ d => (s, false) } |
        ident ^^ { s => (s, true) }

    protected lazy val columnWithType: ExpressionParser.PackratParser[(String, String)] =
      ident ~ dataType ^^ {
        case col ~ dt => (col, dt)
      }

    protected lazy val dataType: ExpressionParser.PackratParser[String] =
      VARCHAR | INTEGER | INT | FLOAT | DOUBLE | DATE | TIMESTAMP | BOOLEAN | BOOL | STRING | LONG | BIGINT

    protected lazy val quotedStr =
      ("'" + """([^'\p{Cntrl}\\]|\\[\\'"bfnrt]|\\u[a-fA-F0-9]{4})*""" + "'").r ^^ {
        str => str.substring(1, str.length - 1)
      }


    lazy val orExpression: Parser[BinaryExpr] = OR ~> mayBeNested ^^ { case e => OrExpr(e) }

    lazy val andExpression: Parser[BinaryExpr] = AND ~> mayBeNested ^^ { case e => AndExpr(e) }

    lazy val mayBeNested: Parser[Expression] = "(" ~> predicate <~ ")" | expression

    lazy val predicate: Parser[Expression] = mayBeNested ~ rep(andExpression | orExpression) ^^ {
      case lhs ~ rhs =>
        if (rhs.nonEmpty) {
          expr(lhs, rhs)
        } else {
          lhs
        }
    }

    lazy val expression: ExpressionParser.PackratParser[Expression] = alias

    lazy val limit: ExpressionParser.PackratParser[Int] = LIMIT ~> ident ^^ (_.toInt)

    lazy val functionWithAlias: Parser[Expression] = function ~ (AS ~> ident).? ^^ {
      case f ~ a => a.map { s =>
        Naming(f, s)
      }.getOrElse(f)
    }

    protected lazy val function: Parser[Expression] =
      (SUM ~> "(" ~> expression <~ ")" ^^ { case exp => Sum(exp) }
        | COUNT ~ "(" ~> "*" <~ ")" ^^ { case _ => Count(Literal(1)) }
        | COUNT ~ "(" ~> expression <~ ")" ^^ { case exp => Count(exp) }
        | AVG ~ "(" ~> expression <~ ")" ^^ { case exp => Avg(exp) }
        | MIN ~ "(" ~> expression <~ ")" ^^ { case exp => Min(exp) }
        | MAX ~ "(" ~> expression <~ ")" ^^ { case exp => Max(exp) }
        | (SUBSTR | SUBSTRING) ~ "(" ~> expression ~ ("," ~> expression) <~ ")" ^^ { case s ~ p => Substring(s, p, Literal(Integer.MAX_VALUE)) }
        | (SUBSTR | SUBSTRING) ~ "(" ~> expression ~ ("," ~> expression) ~ ("," ~> expression) <~ ")" ^^ { case s ~ p ~ l => Substring(s, p, l) }
        | ABS ~ "(" ~> expression <~ ")" ^^ { case exp => Abs(exp) }
        | CAST ~ "(" ~> expression ~ AS ~ typeInformation <~ ")" ^^ { case exp ~ a ~ t => Cast(exp, t) }
        )

    protected lazy val typeInformation: Parser[TypeInformation[_]] =
      (
        VARCHAR ^^ { case s => BasicTypeInfo.STRING_TYPE_INFO }
          | INTEGER ^^ { case s => BasicTypeInfo.INT_TYPE_INFO }
          | INT ^^ { case s => BasicTypeInfo.INT_TYPE_INFO }
          | FLOAT ^^ { case s => BasicTypeInfo.FLOAT_TYPE_INFO }
          | DOUBLE ^^ { case s => BasicTypeInfo.DOUBLE_TYPE_INFO }
          | DATE ^^ { case s => BasicTypeInfo.DATE_TYPE_INFO }
          | TIMESTAMP ^^ { case s => BasicTypeInfo.DATE_TYPE_INFO }
          | BOOLEAN ^^ { case s => BasicTypeInfo.BOOLEAN_TYPE_INFO }
          | BOOL ^^ { case s => BasicTypeInfo.BOOLEAN_TYPE_INFO }
          | STRING ^^ { case s => BasicTypeInfo.STRING_TYPE_INFO }
          | LONG ^^ { case s => BasicTypeInfo.LONG_TYPE_INFO }
          | BIGINT ^^ { case s => BasicTypeInfo.LONG_TYPE_INFO }
        )

    protected val CAST = ExpressionParser.Keyword("CAST")
    protected val EQ = ExpressionParser.Keyword("=")
    protected val SUM = ExpressionParser.Keyword("SUM")
    protected val DISTINCT = ExpressionParser.Keyword("DISTINCT")
    protected val COUNT = ExpressionParser.Keyword("COUNT")
    protected val APPROXIMATE = ExpressionParser.Keyword("APPROXIMATE")
    protected val FIRST = ExpressionParser.Keyword("FIRST")
    protected val LAST = ExpressionParser.Keyword("LAST")
    protected val AVG = ExpressionParser.Keyword("AVG")
    protected val MIN = ExpressionParser.Keyword("MIN")
    protected val MAX = ExpressionParser.Keyword("MAX")
    protected val UPPER = ExpressionParser.Keyword("UPPER")
    protected val LOWER = ExpressionParser.Keyword("LOWER")
    protected val SUBSTR = ExpressionParser.Keyword("SUBSTR")
    protected val SUBSTRING = ExpressionParser.Keyword("SUBSTRING")
    protected val COALESCE = ExpressionParser.Keyword("COALESCE")
    protected val SQRT = ExpressionParser.Keyword("SQRT")
    protected val ABS = ExpressionParser.Keyword("ABS")
    protected val VARCHAR = ExpressionParser.Keyword("VARCHAR")
    protected val INTEGER = ExpressionParser.Keyword("INTEGER")
    protected val INT = ExpressionParser.Keyword("INT")
    protected val LONG = ExpressionParser.Keyword("LONG")
    protected val BIGINT = ExpressionParser.Keyword("BIGINT")
    protected val FLOAT = ExpressionParser.Keyword("FLOAT")
    protected val DOUBLE = ExpressionParser.Keyword("DOUBLE")
    protected val DATE = ExpressionParser.Keyword("DATE")
    protected val STRING = ExpressionParser.Keyword("STRING")
    protected val TIMESTAMP = ExpressionParser.Keyword("TIMESTAMP")
    protected val BOOLEAN = ExpressionParser.Keyword("BOOLEAN")
    protected val BOOL = ExpressionParser.Keyword("BOOL")
    protected val TABLE = ExpressionParser.Keyword("TABLE")
    protected val SELECT = ExpressionParser.Keyword("SELECT")
    protected val FROM = ExpressionParser.Keyword("FROM")
    protected val WHERE = ExpressionParser.Keyword("WHERE")
    protected val JOIN = ExpressionParser.Keyword("JOIN")
    protected val GROUP = ExpressionParser.Keyword("GROUP")
    protected val ORDER = ExpressionParser.Keyword("ORDER")
    protected val ASC = ExpressionParser.Keyword("ASC")
    protected val DESC = ExpressionParser.Keyword("DESC")

    protected val LIMIT = ExpressionParser.Keyword("LIMIT")
    protected val BY = ExpressionParser.Keyword("BY")
    protected val ON = ExpressionParser.Keyword("ON")
    protected val AND = ExpressionParser.Keyword("AND")
    protected val OR = ExpressionParser.Keyword("OR")
    protected val CREATE = ExpressionParser.Keyword("CREATE")
    protected val LOAD = ExpressionParser.Keyword("LOAD")
    protected val INTO = ExpressionParser.Keyword("INTO")
    protected val INNER = ExpressionParser.Keyword("INNER")
    protected val SEMI = ExpressionParser.Keyword("SEMI")
    protected val LEFT = ExpressionParser.Keyword("LEFT")
    protected val RIGHT = ExpressionParser.Keyword("RIGHT")
    protected val FULL = ExpressionParser.Keyword("FULL")
    protected val OUTER = ExpressionParser.Keyword("OUTER")
    protected val NOT = ExpressionParser.Keyword("NOT")
    protected val BETWEEN = ExpressionParser.Keyword("BETWEEN")
    protected val DELIMITED = ExpressionParser.Keyword("DELIMITED")
    protected val WITH = ExpressionParser.Keyword("WITH")
    protected val NULL = ExpressionParser.Keyword("NULL")
    protected val EMPTY = ExpressionParser.Keyword("EMPTY")
    protected val NO = ExpressionParser.Keyword("NO")
    protected val DEFAULTS = ExpressionParser.Keyword("DEFAULTS")

    protected implicit def asParser(k: Keyword): ExpressionParser.PackratParser[String] = allCaseVersions(k.str).map(x => x: Parser[String]).reduce(_ | _)

    /** Generate all variations of upper and lower case of a given string */
    def allCaseVersions(s: String, prefix: String = ""): Stream[String] = {
      if (s == "") {
        Stream(prefix)
      } else {
        allCaseVersions(s.tail, prefix + s.head.toLower) ++
          allCaseVersions(s.tail, prefix + s.head.toUpper)
      }
    }

  }


  class TableDdlParser extends ExprParser {
    def createOrLoadOrSelect: ExpressionParser.PackratParser[Function] = create | load | select

    override def apply(in: Input): ParseResult[Function] = createOrLoadOrSelect(new PackratReader[ExpressionParser.Elem](in))

    def parse(input: String):SqlSupport.Function = parseAll(createOrLoadOrSelect, input) match {
      case s: Success[Function] => s.get
      case e: Error =>
        val msg = "Cannot parse [" + input + "] because " + e.msg
        throw new IllegalArgumentException(msg)
      case f: Failure =>
        val msg = "Cannot parse [" + input + "] because " + f.msg
        throw new IllegalArgumentException(msg)
    }

  }

  case class Keyword(str: scala.Predef.String) extends scala.AnyRef with scala.Product with scala.Serializable

  def expr(e: Expression, l: List[BinaryExpr]): Expression = {
    var expression: Expression = e
    if (l.nonEmpty) {
      var booleanExpr: Expression = if (l.head.isAnd) {
        new And(e, l.head.expr)
      } else {
        new Or(e, l.head.expr)
      }
      l.tail.foreach {
        case o: OrExpr => booleanExpr = new Or(booleanExpr, o.expr)
        case a: BinaryExpr => booleanExpr = new And(booleanExpr, a.expr)
      }
      expression = booleanExpr
    }
    expression
  }

}

// scalastyle:off
/**
 * Tests for the Parser
 */
object SqlSupportTest {

  val parser = new TableDdlParser

  def main(args: Array[String]) {
    val create =
      """CREATE TABLE person (first_name VARCHAR, last_name VARCHAR, age INTEGER, expYear INT, married BOOLEAN) """
    println(parser.parse(create))
    val load =
      """LOAD 'file:///usrs/juin/io/persons' delimited by ',' WITH NULL 'NA' NO DEFAULTS into person  """
    println(parser.parse(load))
    val load2 =
      """LOAD 'file:///usrs/juin/io/persons' delimited by ' '  into person  """
    println(parser.parse(load2))
    val select = """SELECT a,b,c,d from e"""
    val select1 = """SELECT SUM(a),COUNT(b),c,d from e,f"""
    val select2 = """SELECT a,b,c,d from e where a=2"""
    val select3 = """SELECT a,b,c,d from e,f where a=2 OR (b=1 AND c=2)"""
    val select4 = """SELECT a,b,c,d from e ,f where a=2 AND d=1 OR (b=1 AND c=2) group by a"""
    val select5 = """SELECT a as a1,b,c,d from f join e on (a = c AND c =d) where b=1"""
    val select6 = """SELECT * from e order by a,b DESC,c asc limit 100"""


    println(parser.parse(select))
    println(parser.parse(select1))
    println(parser.parse(select2))
    println(parser.parse(select3))
    println(parser.parse(select4))
    println(parser.parse(select5))
    val s6: Select = parser.parse(select6).asInstanceOf[Select]
    println(s6)
    s6.validate
    val select7 = """SELECT sum(a) as suma,b from e group by suma,b"""
    println(parser.parse(select7))
    val select8 = """SELECT sum(a),b from e group by sum(a),b"""
    println(parser.parse(select8))
    val select9 = """SELECT b,cast(a as bigint) as c from e group by sum(a),b"""
    println(parser.parse(select9))
  }

}

// scalastyle:on