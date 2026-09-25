package app.lernet.config.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import app.lernet.config.model.DnsPolicy

@Entity(tableName = "profiles")
data class ProfileEntity(
    @PrimaryKey val id: String,
    val name: String,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val source: String,
    val selectedOutboundId: String,
    val subscriptionUrl: String?,
    val lastRefreshEpochMs: Long?,
    val dnsJson: String? = null,
    val dnsPolicy: String = DnsPolicy.UNDERLAY.name,
    val canvasLayout: String? = null,
    val sortIndex: Int = 0,
    val modeOverride: String? = null,
)

@Entity(
    tableName = "outbounds",
    indices = [Index("profileId")],
)
data class OutboundEntity(
    @PrimaryKey val id: String,
    val profileId: String,
    val tag: String,
    val type: String,
    val singBoxJson: String,
)

@Entity(
    tableName = "rule_nodes",
    indices = [Index("profileId"), Index("parentId")],
)
data class RuleNodeEntity(
    @PrimaryKey val id: String,
    val profileId: String,
    val parentId: String?,
    val enabled: Boolean,
    val sortIndex: Int,
    val action: String,
    val appsJson: String,
    val domainsJson: String,
    val suffixesJson: String,
    val cidrsJson: String,
    val geoipJson: String,
    val pipeName: String = "",
    val blocksJson: String = "",
    val title: String = "",
    val processesJson: String = "[]",
)

@Entity(tableName = "groups")
data class GroupEntity(
    @PrimaryKey val id: String,
    val name: String,
    val sortIndex: Int = 0,
    val canvasLayout: String? = null,
    val autoFailover: Boolean = false,
)

@Entity(
    tableName = "group_members",
    primaryKeys = ["groupId", "profileId"],
    indices = [Index("profileId")],
)
data class GroupMemberEntity(
    val groupId: String,
    val profileId: String,
    val sortIndex: Int,
)
