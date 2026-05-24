package top.nkbe.npatch.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import top.nkbe.npatch.database.entity.Module

@Dao
interface ModuleDao {

    @Query("SELECT * FROM module WHERE pkgName = :pkgName")
    suspend fun getModule(pkgName: String): Module?

    @Query("SELECT * FROM module")
    suspend fun getAll(): List<Module>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(module: Module)

    @Update
    suspend fun update(module: Module)

    @Delete
    suspend fun delete(module: Module)
}
