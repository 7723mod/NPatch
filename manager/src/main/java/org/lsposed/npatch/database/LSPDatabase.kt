package org.lsposed.npatch.database

import androidx.room.Database
import androidx.room.RoomDatabase
import org.lsposed.npatch.database.dao.ModuleDao
import org.lsposed.npatch.database.dao.ScopeDao

import org.lsposed.npatch.database.entity.Module
import org.lsposed.npatch.database.entity.Scope

@Database(entities = [Module::class, Scope::class], version = 1)
abstract class LSPDatabase : RoomDatabase() {
    abstract fun moduleDao(): ModuleDao
    abstract fun scopeDao(): ScopeDao
}
