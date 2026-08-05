package top.nkbe.npatch.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RewriteQueriesToDropUnusedColumns
import top.nkbe.npatch.database.entity.LoadedModule
import top.nkbe.npatch.database.entity.Scope

@Dao
interface ScopeDao {

    @RewriteQueriesToDropUnusedColumns
    @Query("SELECT * FROM LoadedModule INNER JOIN scope ON LoadedModule.pkgName = scope.modulePkgName WHERE scope.appPkgName = :appPkgName")
    suspend fun getModulesForApp(appPkgName: String): List<LoadedModule>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(scope: Scope)

    @Delete
    suspend fun delete(scope: Scope)

    @Query("SELECT appPkgName FROM scope WHERE modulePkgName = :modulePkgName")
    suspend fun getAppsForModule(modulePkgName: String): List<String>

    @Query("SELECT DISTINCT modulePkgName FROM scope")
    suspend fun getScopedModulePackageNames(): List<String>
}
