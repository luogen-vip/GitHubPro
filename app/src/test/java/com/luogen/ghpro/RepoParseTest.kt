package com.luogen.ghpro

import com.luogen.ghpro.data.Api
import com.luogen.ghpro.data.Repo
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

class RepoParseTest {

    private val o = "luogen-vip"
    private val r = "GitHubPro"

    @Test
    fun allListEndpointsReturnTypedObjects() = runBlocking {
        val tok = System.getenv("GH_TOKEN")
        if (tok.isNullOrBlank()) { println("NO_TOKEN"); return@runBlocking }
        Api.token = tok

        fun check(label: String, list: List<Any>, expect: Class<*>) {
            val bad = list.filter { !expect.isInstance(it) }
            println("$label size=${list.size} bad=${bad.size} first=${list.firstOrNull()?.javaClass?.name}")
            assertTrue("$label 解析出非 ${expect.simpleName} 元素: ${bad.take(1).map { it.javaClass.name }}", bad.isEmpty())
        }

        val pg = Api.myRepos(1, 60, "updated")
        check("myRepos", pg.items, Repo::class.java)
        pg.items.firstOrNull()?.let { println("repo=${it.full_name} private=${it.isPrivate} owner=${it.owner?.login}") }

        val st = Api.starred(1)
        check("starred", st.items, Repo::class.java)
        st.items.firstOrNull()?.let { println("starred=${it.full_name} starred_at=${it.starred_at}") }

        val br = Api.branches(o, r)
        check("branches", br, com.luogen.ghpro.data.Branch::class.java)

        val cm = Api.commits(o, r, page = 1)
        check("commits", cm.items, com.luogen.ghpro.data.GitCommit::class.java)

        val isu = Api.issues(o, r, "all", 1)
        check("issues", isu.items, com.luogen.ghpro.data.Issue::class.java)

        val rel = Api.releases(o, r)
        check("releases", rel.items, com.luogen.ghpro.data.Release::class.java)

        val wf = Api.workflows(o, r)
        check("workflows", wf, com.luogen.ghpro.data.Workflow::class.java)

        val nt = Api.notifications()
        check("notifications", nt, com.luogen.ghpro.data.Notification::class.java)

        val me = Api.me()
        println("me=${me.login} repos=${me.public_repos}")
    }
}
