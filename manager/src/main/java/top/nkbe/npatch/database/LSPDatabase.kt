package top.nkbe.npatch.database

import androidx.room.Database
import androidx.room.RoomDatabase
import top.nkbe.npatch.database.dao.ModuleDao
import top.nkbe.npatch.database.dao.ScopeDao

import top.nkbe.npatch.database.entity.Module
import top.nkbe.npatch.database.entity.Scope

@Database(entities = [Module::class, Scope::class], version = 1, exportSchema = false)
abstract class LSPDatabase : RoomDatabase() {
    abstract fun moduleDao(): ModuleDao
    abstract fun scopeDao(): ScopeDao
}
