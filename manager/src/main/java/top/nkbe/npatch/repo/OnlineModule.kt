package top.nkbe.npatch.repo

import com.google.gson.annotations.Expose
import com.google.gson.annotations.SerializedName

class OnlineModule {
    @field:SerializedName("name")
    @field:Expose
    var name: String? = null

    @field:SerializedName("description")
    @field:Expose
    var description: String? = null

    @field:SerializedName("url")
    @field:Expose
    var url: String? = null

    @field:SerializedName("homepageUrl")
    @field:Expose
    var homepageUrl: String? = null

    @field:SerializedName("collaborators")
    @field:Expose
    var collaborators: List<Collaborator> = emptyList()

    @field:SerializedName("latestRelease")
    @field:Expose
    var latestRelease: String? = null

    @field:SerializedName("latestReleaseTime")
    @field:Expose
    var latestReleaseTime: String? = null

    @field:SerializedName("latestBetaRelease")
    @field:Expose
    var latestBetaRelease: String? = null

    @field:SerializedName("latestBetaReleaseTime")
    @field:Expose
    var latestBetaReleaseTime: String? = null

    @field:SerializedName("latestSnapshotRelease")
    @field:Expose
    var latestSnapshotRelease: String? = null

    @field:SerializedName("latestSnapshotReleaseTime")
    @field:Expose
    var latestSnapshotReleaseTime: String? = null

    @field:SerializedName("releases")
    @field:Expose
    var releases: List<Release> = emptyList()

    @field:SerializedName("betaReleases")
    @field:Expose
    var betaReleases: List<Release> = emptyList()

    @field:SerializedName("snapshotReleases")
    @field:Expose
    var snapshotReleases: List<Release> = emptyList()

    @field:SerializedName("readme")
    @field:Expose
    var readme: String? = null

    @field:SerializedName("readmeHTML")
    @field:Expose
    var readmeHTML: String? = null

    @field:SerializedName("summary")
    @field:Expose
    var summary: String? = null

    @field:SerializedName("scope")
    @field:Expose
    var scope: List<String> = emptyList()

    @field:SerializedName("sourceUrl")
    @field:Expose
    var sourceUrl: String? = null

    @field:SerializedName("hide")
    @field:Expose
    var hide: Boolean? = null

    @field:SerializedName("additionalAuthors")
    @field:Expose
    var additionalAuthors: List<Any>? = null

    @field:SerializedName("updatedAt")
    @field:Expose
    var updatedAt: String? = null

    @field:SerializedName("createdAt")
    @field:Expose
    var createdAt: String? = null

    @field:SerializedName("stargazerCount")
    @field:Expose
    var stargazerCount: Int? = null

    var releasesLoaded: Boolean = false
}
