package com.luogen.ghpro.data

import com.google.gson.annotations.SerializedName

data class User(
    val login: String = "",
    val id: Long = 0,
    val node_id: String? = null,
    val avatar_url: String? = null,
    val html_url: String? = null,
    val name: String? = null,
    val company: String? = null,
    val blog: String? = null,
    val location: String? = null,
    val email: String? = null,
    val bio: String? = null,
    val twitter_username: String? = null,
    val public_repos: Int = 0,
    val public_gists: Int = 0,
    val followers: Int = 0,
    val following: Int = 0,
    val contributions: Int = 0,
    val created_at: String? = null,
    val updated_at: String? = null,
    val type: String? = null,
    val hireable: Boolean? = null,
    val site_admin: Boolean? = null
)

data class RepoOwner(
    val login: String = "",
    val avatar_url: String? = null,
    val html_url: String? = null,
    val type: String? = null
)

data class License(
    val key: String? = null,
    val name: String? = null,
    val spdx_id: String? = null
)

data class RepoPermissions(
    val admin: Boolean = false,
    val maintain: Boolean = false,
    val push: Boolean = false,
    val triage: Boolean = false,
    val pull: Boolean = false
)

data class Repo(
    val id: Long = 0,
    val node_id: String? = null,
    val name: String = "",
    val full_name: String = "",
    val owner: RepoOwner? = null,
    val description: String? = null,
    @SerializedName("private") val isPrivate: Boolean = false,
    val fork: Boolean = false,
    val html_url: String? = null,
    val url: String? = null,
    val homepage: String? = null,
    val language: String? = null,
    val forks_count: Int = 0,
    val stargazers_count: Int = 0,
    val watchers_count: Int = 0,
    val open_issues_count: Int = 0,
    val subscribers_count: Int = 0,
    val network_count: Int = 0,
    val size: Long = 0,
    val default_branch: String? = "main",
    val topics: List<String>? = null,
    val has_issues: Boolean = true,
    val has_projects: Boolean = true,
    val has_wiki: Boolean = true,
    val has_discussions: Boolean = false,
    val has_pages: Boolean = false,
    val archived: Boolean = false,
    val disabled: Boolean = false,
    val visibility: String? = null,
    val license: License? = null,
    val permissions: RepoPermissions? = null,
    val created_at: String? = null,
    val updated_at: String? = null,
    val pushed_at: String? = null,
    val starred_at: String? = null,
    val parent: Repo? = null,
    val source: Repo? = null
)

data class Branch(
    val name: String = "",
    val commit: BranchCommit? = null,
    val protected: Boolean = false
)

data class BranchCommit(val sha: String = "", val url: String? = null)

data class ContentItem(
    val name: String = "",
    val path: String = "",
    val sha: String = "",
    val size: Long = 0,
    val type: String = "",
    val encoding: String? = null,
    val content: String? = null,
    val download_url: String? = null,
    val html_url: String? = null,
    val git_url: String? = null
)

data class CommitAuthor(
    val name: String? = null,
    val email: String? = null,
    val date: String? = null,
    val login: String? = null,
    val avatar_url: String? = null
)

data class CommitDetail(
    val message: String? = null,
    val author: CommitAuthor? = null,
    val committer: CommitAuthor? = null,
    val comment_count: Int = 0
)

data class GitCommit(
    val sha: String = "",
    val node_id: String? = null,
    val commit: CommitDetail? = null,
    val author: User? = null,
    val committer: User? = null,
    val html_url: String? = null,
    val parents: List<BranchCommit>? = null,
    val stats: CommitStats? = null,
    val files: List<PRFile>? = null
)

data class CommitStats(val total: Int = 0, val additions: Int = 0, val deletions: Int = 0)

data class Label(val id: Long = 0, val name: String = "", val color: String = "ededed", val description: String? = null)

data class Milestone(val id: Long = 0, val title: String = "", val number: Int = 0, val state: String? = null)

data class Issue(
    val id: Long = 0,
    val number: Int = 0,
    val title: String = "",
    val body: String? = null,
    val state: String = "open",
    val state_reason: String? = null,
    val user: User? = null,
    val labels: List<Label>? = null,
    val assignees: List<User>? = null,
    val milestone: Milestone? = null,
    val comments: Int = 0,
    val locked: Boolean = false,
    val created_at: String? = null,
    val updated_at: String? = null,
    val closed_at: String? = null,
    val html_url: String? = null,
    val pull_request: Any? = null,
    val repository_url: String? = null
)

data class Comment(
    val id: Long = 0,
    val body: String? = null,
    val user: User? = null,
    val created_at: String? = null,
    val updated_at: String? = null,
    val html_url: String? = null,
    val author_association: String? = null,
    val reactions: Reactions? = null
)

data class Reactions(
    val total_count: Long = 0,
    @SerializedName("+1") val plusOne: Int = 0,
    @SerializedName("-1") val minusOne: Int = 0,
    val laugh: Int = 0,
    val hooray: Int = 0,
    val confused: Int = 0,
    val heart: Int = 0,
    val rocket: Int = 0,
    val eyes: Int = 0
)

data class PRRef(val label: String? = null, val ref: String = "", val sha: String = "", val user: User? = null, val repo: Repo? = null)

data class PullRequest(
    val id: Long = 0,
    val number: Int = 0,
    val title: String = "",
    val body: String? = null,
    val state: String = "open",
    val user: User? = null,
    val created_at: String? = null,
    val updated_at: String? = null,
    val closed_at: String? = null,
    val merged_at: String? = null,
    val merged: Boolean = false,
    val mergeable: Boolean? = null,
    val mergeable_state: String? = null,
    val draft: Boolean = false,
    val commits: Int = 0,
    val additions: Int = 0,
    val deletions: Int = 0,
    val changed_files: Int = 0,
    val comments: Int = 0,
    val review_comments: Int = 0,
    val head: PRRef? = null,
    val base: PRRef? = null,
    val html_url: String? = null,
    val labels: List<Label>? = null,
    val requested_reviewers: List<User>? = null,
    val maintainer_can_modify: Boolean? = null
)

data class PRFile(
    val sha: String? = null,
    val filename: String = "",
    val status: String? = null,
    val additions: Int = 0,
    val deletions: Int = 0,
    val changes: Int = 0,
    val patch: String? = null,
    val raw_url: String? = null
)

data class Workflow(
    val id: Long = 0,
    val node_id: String? = null,
    val name: String = "",
    val path: String = "",
    val state: String = "",
    val html_url: String? = null,
    val badge_url: String? = null,
    val created_at: String? = null,
    val updated_at: String? = null
)

data class WorkflowRef(val id: Long = 0, val name: String? = null, val path: String? = null)

data class RunActor(
    val login: String = "",
    val avatar_url: String? = null,
    val html_url: String? = null
)

data class Step(
    val name: String = "",
    val status: String = "",
    val conclusion: String? = null,
    val number: Int = 0,
    val started_at: String? = null,
    val completed_at: String? = null
)

data class Job(
    val id: Long = 0,
    val run_id: Long = 0,
    val run_url: String? = null,
    val name: String = "",
    val status: String = "",
    val conclusion: String? = null,
    val started_at: String? = null,
    val completed_at: String? = null,
    val steps: List<Step>? = null,
    val runner_name: String? = null,
    val runner_group_name: String? = null,
    val labels: List<String>? = null,
    val html_url: String? = null,
    val head_sha: String? = null
)

data class Run(
    val id: Long = 0,
    val name: String? = null,
    val display_title: String? = null,
    val workflow_id: Long = 0,
    val head_branch: String? = null,
    val head_sha: String? = null,
    val run_number: Int = 0,
    val run_attempt: Int = 1,
    val event: String? = null,
    val status: String = "",
    val conclusion: String? = null,
    val workflow_ref: String? = null,
    val created_at: String? = null,
    val updated_at: String? = null,
    val run_started_at: String? = null,
    val html_url: String? = null,
    val actor: RunActor? = null,
    val triggering_actor: RunActor? = null,
    val head_commit: CommitDetail? = null,
    val jobs_url: String? = null,
    val logs_url: String? = null,
    val artifacts_url: String? = null
)

data class Artifact(
    val id: Long = 0,
    val node_id: String? = null,
    val name: String = "",
    val size_in_bytes: Long = 0,
    val url: String? = null,
    val archive_download_url: String? = null,
    val expired: Boolean = false,
    val created_at: String? = null,
    val expires_at: String? = null,
    val updated_at: String? = null
)

data class Asset(
    val id: Long = 0,
    val name: String = "",
    val label: String? = null,
    val size: Long = 0,
    val download_count: Int = 0,
    val content_type: String? = null,
    val created_at: String? = null,
    val updated_at: String? = null,
    val browser_download_url: String? = null,
    val url: String? = null
)

data class Release(
    val id: Long = 0,
    val tag_name: String = "",
    val target_commitish: String? = null,
    val name: String? = null,
    val body: String? = null,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val created_at: String? = null,
    val published_at: String? = null,
    val html_url: String? = null,
    val assets: List<Asset>? = null,
    val author: User? = null,
    val assets_url: String? = null,
    val upload_url: String? = null
)

data class NotificationSubject(
    val title: String = "",
    val url: String? = null,
    val latest_comment_url: String? = null,
    val type: String = ""
)

data class Notification(
    val id: String = "",
    val unread: Boolean = false,
    val reason: String? = null,
    val updated_at: String? = null,
    val last_read_at: String? = null,
    val subject: NotificationSubject? = null,
    val repository: Repo? = null,
    val url: String? = null
)

/** App 内本地动态：工作流、PR、发行版等 GitHub 通知接口不一定推送的变化，也要能在“通知”页看到。 */
data class LocalNotice(
    val id: String = "",
    val title: String = "",
    val body: String = "",
    val route: String? = null,
    val kind: String = "",
    val time: Long = 0L
)

data class GhEvent(
    val id: String = "",
    val type: String? = null,
    val actor: User? = null,
    val repo: Repo? = null,
    val org: RepoOwner? = null,
    val payload: Any? = null,
    val created_at: String? = null
)

data class SearchRepos(val total_count: Long = 0, val incomplete_results: Boolean = false, val items: List<Repo> = emptyList())
data class SearchUsers(val total_count: Long = 0, val incomplete_results: Boolean = false, val items: List<User> = emptyList())
data class SearchIssues(val total_count: Long = 0, val incomplete_results: Boolean = false, val items: List<Issue> = emptyList())
data class SearchCode(val total_count: Long = 0, val incomplete_results: Boolean = false, val items: List<CodeItem> = emptyList())

data class CodeItem(
    val name: String = "",
    val path: String = "",
    val sha: String? = null,
    val html_url: String? = null,
    val repository: Repo? = null
)

data class RateLimitResource(val limit: Int = 0, val remaining: Int = 0, val reset: Long = 0, val used: Int = 0)
data class RateLimitCore(val core: RateLimitResource? = null, val search: RateLimitResource? = null, val graphql: RateLimitResource? = null)
data class RateLimitResp(val resources: RateLimitCore? = null)

data class DiscussionAuthor(val login: String = "", val avatarUrl: String? = null, val url: String? = null)
data class DiscussionCategory(val name: String = "", val emoji: String? = null)
data class DiscussionComment(
    val id: String? = null,
    val body: String? = null,
    val author: DiscussionAuthor? = null,
    val createdAt: String? = null,
    val isAnswer: Boolean = false
)
data class DiscussionComments(val totalCount: Int = 0, val nodes: List<DiscussionComment> = emptyList())
data class DiscussionsPage(val enabled: Boolean = false, val items: List<Discussion> = emptyList())

data class Discussion(
    val number: Int = 0,
    val title: String = "",
    val body: String? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
    val author: DiscussionAuthor? = null,
    val category: DiscussionCategory? = null,
    val comments: DiscussionComments? = null,
    val url: String? = null,
    val answerChosenAt: String? = null
)

data class LatestCommit(
    val sha: String = "",
    val message: String? = null,
    val date: String? = null,
    val author: String? = null,
    val avatar: String? = null
)


data class LicenseTpl(
    val key: String = "",
    val name: String = "",
    val spdx_id: String? = null,
    val url: String? = null
)

data class UserStatus(
    val emoji: String? = null,
    val message: String? = null,
    val limitedAvailability: Boolean = false,
    val expiresAt: String? = null
)
