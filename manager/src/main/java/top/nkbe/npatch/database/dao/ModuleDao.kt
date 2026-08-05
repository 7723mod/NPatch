package top.nkbe.npatch.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import top.nkbe.npatch.database.entity.LoadedModule

@Dao
interface ModuleDao {

    @Query("SELECT * FROM LoadedModule WHERE pkgName = :pkgName")
    suspend fun getModule(pkgName: String): LoadedModule?

    @Query("SELECT * FROM LoadedModule")
    suspend fun getAll(): List<LoadedModule>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(LoadedModule: LoadedModule)

    @Update
    suspend fun update(LoadedModule: LoadedModule)

    @Delete
    suspend fun delete(LoadedModule: LoadedModule)
}
