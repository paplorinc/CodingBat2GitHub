package pap.lorinc

import com.beust.klaxon.*
import org.jsoup.Connection
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import pap.lorinc.Crawler.echo
import java.time.Duration
import java.time.LocalDateTime

enum class Language { CPP, JAVA, PYTHON, C, CSHARP, JAVASCRIPT, RUBY, SWIFT, GOLANG }
data class LeetCodeProblem(val submitTime: LocalDateTime, val packageName: String, val link: String, val description: String, val solution: String, val name: String, val runTime: Duration, val language: Language)

object Crawler2 {
    val base = "https://leetcode.com"

    var userId   = "" // your userId here
    var password = "" // your password here

    @JvmStatic fun main(args: Array<String>) {
        val document = login()
        val contents = parseContent(document)
        val commands = generateCommands(contents)
        println(commands())
    }

    private fun parseContent(document: Connection.Response): List<LeetCodeProblem> {
        val visited = hashSetOf<String>()
        val results = mutableListOf<LeetCodeProblem>()
        for (page in 1..10000) {
            val parsedSubmissions = submissions(page, document.cookies())

            val solutions = solutions(document, parsedSubmissions, visited)
            results.addAll(solutions)

            if (parsedSubmissions.boolean("has_next") != true)
                break
        }
        return results.reversed()
    }

    private fun generateCommands(contents: List<LeetCodeProblem>) = {
        val before = """
            >
            >git init
            >
            """.trimMargin(">")

        val git = contents.map { info ->
            val mainJava = "src/main/java/leetcode/${info.packageName}"
            val className = className(info)
            """
            >
            >mkdir -p '$mainJava'
            >${echo(generateMain(info))} > $mainJava/$className.java
            >git add src
            >git commit -m "${info.name}" --date="${info.submitTime}"
            >
            """.trimMargin(">")
        }.joinToString("\n")

        val userName = userId.replace(Regex("@.+$"), "")
        val languages = contents.map { it.language.toString().toLowerCase() }.toSet().joinToString(",")
        val after = """
            >
            >
            >${echo("to install gradle, type: sudo add-apt-repository ppa:cwchien/gradle && sudo apt-get update && sudo apt-get install gradle")}
            >${echo("[![Build Status](https://travis-ci.org/$userName/LeetCodeSolutions.png)](https://travis-ci.org/$userName/LeetCodeSolutions)\n\nSolutions to my [LeetCode](http://LeetCode.com) exercises, exported by [CodingExerciseExtractor](https://github.com/paplorinc/CodingExerciseExtractor).")} > README.md
            >${echo("language: $languages\n\njdk: oraclejdk8\n\nbefore_install: chmod +x gradlew\nscript: ./gradlew clean build --stacktrace")} > .travis.yml
            >gradle init --type java-library --test-framework spock && rm src/test/groovy/LibraryTest.groovy && rm src/main/java/Library.java && git add -A && gradle build
            >
            """.trimMargin(">")
        before + git + after
    }

    private fun generateMain(info: LeetCodeProblem) =
            """
        >package leetcode.${info.packageName};
        >
        >import java.util.*;
        >
        >/**
        > * ${info.description.lines().map(String::trim).joinToString("\n * ")}
        > * Source: ${info.link}
        > */
        >${info.solution}
        >""".trimMargin(">").trim()


    private fun solutions(document: Connection.Response, parsedSubmissions: JsonObject, visited: HashSet<String>): List<LeetCodeProblem> =
            parsedSubmissions.array<JsonObject>("submissions_dump")!!
                    .filter { s -> s.string("status_display") == "Accepted" }
                    .filter { s -> visited.add(s.string("title")!!) }
                    .map { submission ->
                        val solution = Jsoup.connect(base + submission.string("url")).cookies(document.cookies()).get()
                        LeetCodeProblem(
                                submitTime = parseDuration(submission),
                                packageName = parsePackage(submission),
                                link = parseLink(solution),
                                description = getDescription(solution),
                                solution = getSolution(solution),
                                name = getName(submission),
                                runTime = getRunTime(submission),
                                language = parseLanguage(submission)
                        )
                    }

    private fun submissions(page: Int, cookies: MutableMap<String, String>): JsonObject {
        val submissions = Jsoup.connect("$base/api/submissions/my/$page/?format=json").cookies(cookies).ignoreContentType(true).get().body().text()
        val parsedSubmissions = Parser().parse(StringBuilder(submissions)) as JsonObject
        return parsedSubmissions
    }

    private fun login(): Connection.Response {
        val loginForm = Jsoup
                .connect("$base/accounts/login")
                .execute()

        val token = loginForm.parse().select("""input[name="csrfmiddlewaretoken"]""").attr("value")
        return Jsoup
                .connect("$base/accounts/login")
                .method(Connection.Method.POST)
                .referrer("$base/accounts/login")
                .data("csrfmiddlewaretoken", token)
                .data("login", userId)
                .data("password", password)
                .cookies(loginForm.cookies())
                .execute()
    }

    private fun className(info: LeetCodeProblem) = Regex("""public class (\w+)""").find(info.solution)!!.groupValues[1]

    private fun parseDuration(submission: JsonObject): LocalDateTime =
            Regex("""^(?:(\d+) years?)?(?: *(\d+) months?)?(?: *(\d+) weeks?)?(?: *(\d+) +days?)?(?: *(\d+) hours?)?(?: *(\d+) minutes?)?$""")
                    .matchEntire(submission.string("time")!!.replace(Regex("""\W+"""), " ")).let {
                val (year, month, week, day, hour, minute) = it!!.destructured
                LocalDateTime.now()
                        .minusYears(year.toLongOrNull() ?: 0)
                        .minusMonths(month.toLongOrNull() ?: 0)
                        .minusWeeks(week.toLongOrNull() ?: 0)
                        .minusDays(day.toLongOrNull() ?: 0)
                        .minusHours(hour.toLongOrNull() ?: 0)
                        .minusMinutes(minute.toLongOrNull() ?: 0)
            }

    private fun parsePackage(submission: JsonObject): String = submission.string("title")!!.trim().toLowerCase().replace(Regex(""" +(.)""")) { x -> x.groupValues[1].toUpperCase() }
    private fun getDescription(solution: Document): String = solution.select("""meta[name="description"]""").attr("content")
    private fun parseLink(solution: Document): String = base + solution.select("""a[href^="/problems/"]""").first().attr("href")
    private fun getSolution(solution: Document): String {
        val code = Regex("submissionCode: '(.+)',").find(solution.select("script")[7].html())!!.groupValues[1]
        return code.replace(Regex("""\\u(....)""")) { m -> m.groupValues[1].toLong(16).toChar().toString() }
    }

    private fun getName(submission: JsonObject): String = submission.string("title")!!
    private fun getRunTime(submission: JsonObject): Duration = Duration.ofMillis(submission.string("runtime")!!.replace(Regex("""\D+"""), "").toLong())
    private fun parseLanguage(submission: JsonObject): Language = Language.valueOf(submission.string("lang")!!.toUpperCase())
}