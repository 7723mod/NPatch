package top.nkbe.npatch.repo

import com.google.gson.annotations.Expose
import com.google.gson.annotations.SerializedName

data class Release(
    @field:SerializedName("name")
    @field:Expose
    var name: String? = null,

    @field:SerializedName("url")
    @field:Expose
    var url: String? = null,

    @field:SerializedName("description")
    @field:Expose
    var description: String? = null,

    @field:SerializedName("descriptionHTML")
    @field:Expose
    var descriptionHTML: String? = null,

    @field:SerializedName("createdAt")
    @field:Expose
    var createdAt: String? = null,

    @field:SerializedName("publishedAt")
    @field:Expose
    var publishedAt: String? = null,

    @field:SerializedName("updatedAt")
    @field:Expose
    var updatedAt: String? = null,

    @field:SerializedName("tagName")
    @field:Expose
    var tagName: String? = null,

    @field:SerializedName("isPrerelease")
    @field:Expose
    var isPrerelease: Boolean? = null,

    @field:SerializedName("releaseAssets")
    @field:Expose
    var releaseAssets: List<ReleaseAsset> = emptyList(),
)
