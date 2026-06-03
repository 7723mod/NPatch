package top.nkbe.npatch.repo

import com.google.gson.annotations.Expose
import com.google.gson.annotations.SerializedName

data class ReleaseAsset(
    @field:SerializedName("name")
    @field:Expose
    var name: String? = null,
    @field:SerializedName("contentType")
    @field:Expose
    var contentType: String? = null,
    @field:SerializedName("downloadUrl")
    @field:Expose
    var downloadUrl: String? = null,
    @field:SerializedName("downloadCount")
    @field:Expose
    var downloadCount: Int = 0,
    @field:SerializedName("size")
    @field:Expose
    var size: Int = 0,
)
