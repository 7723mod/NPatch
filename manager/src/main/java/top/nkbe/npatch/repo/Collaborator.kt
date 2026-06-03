package top.nkbe.npatch.repo

import com.google.gson.annotations.Expose
import com.google.gson.annotations.SerializedName

data class Collaborator(
    @field:SerializedName("login")
    @field:Expose
    var login: String? = null,
    @field:SerializedName("name")
    @field:Expose
    var name: String? = null,
)
