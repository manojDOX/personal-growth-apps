package com.selfapps.guardlock.data

import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.selfapps.guardlock.lock.Perms
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import java.security.SecureRandom

/**
 * All Firestore access. No server code: the security rules in firestore.rules are what
 * stop the guarded phone from loosening locks.
 */
object PairRepository {
    private val auth get() = FirebaseAuth.getInstance()
    private val db get() = FirebaseFirestore.getInstance()
    private fun pairs() = db.collection("pairs")
    private fun apps(pairId: String) = pairs().document(pairId).collection("apps")

    // No 0/O/1/I so the code is easy to read out loud.
    private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    const val CODE_LENGTH = 8

    /** Anonymous sign-in: no email/SMS needed, costs nothing. */
    suspend fun uid(): String =
        auth.currentUser?.uid ?: auth.signInAnonymously().await().user!!.uid

    /** Writes queue forever when offline; surface that as an error instead of a hung spinner. */
    private suspend fun <T : Any> net(block: suspend () -> T): T =
        withTimeoutOrNull(20_000) { block() } ?: error("Timed out - check your internet connection")

    // ---------- pairing ----------

    suspend fun createPair(): String = net {
        val uid = uid()
        val rnd = SecureRandom()
        val code = buildString { repeat(CODE_LENGTH) { append(ALPHABET[rnd.nextInt(ALPHABET.length)]) } }
        pairs().document(code).set(
            mapOf(
                "guardianUid" to uid,
                "guardedUid" to null,
                "createdAt" to FieldValue.serverTimestamp(),
                "health" to emptyMap<String, Any>(),
            )
        ).await()
        code
    }

    suspend fun joinPair(rawCode: String): String = net {
        val code = rawCode.uppercase().filter { it in ALPHABET }
        require(code.length == CODE_LENGTH) { "Enter the $CODE_LENGTH-character code" }
        val uid = uid()
        val ref = pairs().document(code)
        val snap = ref.get().await()
        check(snap.exists()) { "No pairing found for that code" }
        val existing = snap.getString("guardedUid")
        check(existing == null || existing == uid) { "That code is already used by another phone" }
        if (existing == null) ref.update("guardedUid", uid).await()
        code
    }

    // ---------- live data ----------

    fun pairFlow(pairId: String): Flow<PairInfo?> = flow {
        uid()
        emitAll(rawPairFlow(pairId))
    }

    fun appsFlow(pairId: String): Flow<List<AppRule>> = flow {
        uid()
        emitAll(rawAppsFlow(pairId))
    }

    private fun rawPairFlow(pairId: String): Flow<PairInfo?> = callbackFlow {
        val reg = pairs().document(pairId).addSnapshotListener { snap, err ->
            if (err != null) {
                close(err)
                return@addSnapshotListener
            }
            trySend(snap?.toPair())
        }
        awaitClose { reg.remove() }
    }

    private fun rawAppsFlow(pairId: String): Flow<List<AppRule>> = callbackFlow {
        val reg = apps(pairId).addSnapshotListener { snap, err ->
            if (err != null) {
                close(err)
                return@addSnapshotListener
            }
            if (snap != null) {
                trySend(snap.documents.map { it.toRule() }.sortedBy { it.name.lowercase() })
            }
        }
        awaitClose { reg.remove() }
    }

    // ---------- mutations ----------

    suspend fun addApp(pairId: String, pkg: String, name: String, addedBy: String) = net {
        apps(pairId).document(pkg).set(
            mapOf("name" to name, "addedBy" to addedBy, "locked" to true, "unlockUntil" to null)
        ).await()
        true
    }

    /** Guardian: lock now (true) or release indefinitely (false). Clears any timed window. */
    suspend fun setLocked(pairId: String, pkg: String, locked: Boolean) = net {
        apps(pairId).document(pkg).update("locked", locked, "unlockUntil", null).await()
        true
    }

    /** Guardian: keep locked by default but open until [untilMillis]. */
    suspend fun unlockUntil(pairId: String, pkg: String, untilMillis: Long) = net {
        apps(pairId).document(pkg).update("locked", true, "unlockUntil", untilMillis).await()
        true
    }

    suspend fun removeApp(pairId: String, pkg: String) = net {
        apps(pairId).document(pkg).delete().await()
        true
    }

    suspend fun sendHeartbeat(pairId: String, s: Perms.Snapshot) = net {
        uid()
        pairs().document(pairId).update(
            mapOf(
                "health.lastHeartbeat" to FieldValue.serverTimestamp(),
                "health.accessibilityOn" to s.accessibility,
                "health.batteryExempt" to s.batteryExempt,
                "health.deviceAdmin" to s.deviceAdmin,
                "health.notificationsOn" to s.notifications,
            )
        ).await()
        true
    }

    // ---------- mapping ----------

    private fun DocumentSnapshot.toPair(): PairInfo? {
        if (!exists()) return null
        val guardian = getString("guardianUid") ?: return null
        @Suppress("UNCHECKED_CAST")
        val h = get("health") as? Map<String, Any?> ?: emptyMap()
        return PairInfo(
            id = id,
            guardianUid = guardian,
            guardedUid = getString("guardedUid"),
            health = Health(
                lastHeartbeat = (h["lastHeartbeat"] as? Timestamp)?.toDate()?.time,
                accessibilityOn = h["accessibilityOn"] == true,
                batteryExempt = h["batteryExempt"] == true,
                deviceAdmin = h["deviceAdmin"] == true,
                notificationsOn = h["notificationsOn"] == true,
            ),
        )
    }

    private fun DocumentSnapshot.toRule() = AppRule(
        pkg = id,
        name = getString("name") ?: id,
        locked = getBoolean("locked") ?: true, // fail closed
        unlockUntil = getLong("unlockUntil"),
        addedBy = getString("addedBy") ?: "",
    )
}
