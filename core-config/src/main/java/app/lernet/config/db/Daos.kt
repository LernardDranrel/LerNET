package app.lernet.config.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ProfileDao {
    @Query("SELECT * FROM profiles ORDER BY sortIndex ASC, updatedAtEpochMs DESC")
    fun observeProfiles(): Flow<List<ProfileEntity>>

    @Query("SELECT * FROM profiles ORDER BY sortIndex ASC, updatedAtEpochMs DESC")
    suspend fun listProfiles(): List<ProfileEntity>

    @Query("SELECT * FROM profiles WHERE id = :id")
    suspend fun getProfile(id: String): ProfileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProfile(entity: ProfileEntity)

    @Update
    suspend fun updateProfile(entity: ProfileEntity)

    @Transaction
    suspend fun updateProfileOrder(rows: List<ProfileEntity>) {
        rows.forEach { updateProfile(it) }
    }

    @Query("DELETE FROM profiles WHERE id = :id")
    suspend fun deleteProfile(id: String)
}

@Dao
interface OutboundDao {
    @Query("SELECT * FROM outbounds")
    fun observeAll(): Flow<List<OutboundEntity>>

    @Query("SELECT * FROM outbounds WHERE profileId = :profileId")
    suspend fun listForProfile(profileId: String): List<OutboundEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<OutboundEntity>)

    @Query("DELETE FROM outbounds WHERE profileId = :profileId")
    suspend fun deleteForProfile(profileId: String)
}

@Dao
interface RuleNodeDao {
    @Query("SELECT * FROM rule_nodes")
    fun observeAll(): Flow<List<RuleNodeEntity>>

    @Query("SELECT * FROM rule_nodes")
    suspend fun listAll(): List<RuleNodeEntity>

    @Query("SELECT * FROM rule_nodes WHERE profileId = :profileId ORDER BY sortIndex ASC")
    suspend fun listForProfile(profileId: String): List<RuleNodeEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<RuleNodeEntity>)

    @Query("DELETE FROM rule_nodes WHERE profileId = :profileId")
    suspend fun deleteForProfile(profileId: String)

    @Query("DELETE FROM rule_nodes WHERE id = :id")
    suspend fun deleteNode(id: String)
}

@Dao
interface GroupDao {
    @Query("SELECT * FROM groups ORDER BY sortIndex ASC, name ASC")
    fun observeGroups(): Flow<List<GroupEntity>>

    @Query("SELECT * FROM groups")
    suspend fun listGroups(): List<GroupEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertGroup(entity: GroupEntity)

    @Query("DELETE FROM groups WHERE id = :id")
    suspend fun deleteGroup(id: String)
}

@Dao
interface GroupMemberDao {
    @Query("SELECT * FROM group_members ORDER BY sortIndex ASC")
    fun observeMembers(): Flow<List<GroupMemberEntity>>

    @Query("SELECT * FROM group_members WHERE groupId = :groupId ORDER BY sortIndex ASC")
    suspend fun listForGroup(groupId: String): List<GroupMemberEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<GroupMemberEntity>)

    @Query("DELETE FROM group_members WHERE groupId = :groupId")
    suspend fun deleteForGroup(groupId: String)

    @Query("DELETE FROM group_members WHERE profileId = :profileId")
    suspend fun deleteForProfile(profileId: String)

    @Transaction
    suspend fun replaceMembers(groupId: String, members: List<GroupMemberEntity>) {
        deleteForGroup(groupId)
        if (members.isNotEmpty()) {
            upsertAll(members)
        }
    }

    @Transaction
    suspend fun replaceAllGroupMembers(groupIds: List<String>, members: List<GroupMemberEntity>) {
        groupIds.forEach { deleteForGroup(it) }
        if (members.isNotEmpty()) upsertAll(members)
    }
}
