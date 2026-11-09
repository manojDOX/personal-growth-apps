package com.selfapps.guardlock.data

import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.SetOptions
import com.selfapps.guardlock.lock.Perms
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import java.security.SecureRandom
import java.time.LocalDate

/**
 * All Firestore access. No server code: the security rules in firestore.rules are what stop a
 * phone's owner from loosening the locks their partner set.
 *
 * Layout: pairs/{id}/phones/{uid}[/apps/{pkg} | /usage/{date}]. "phone" is always the uid of the
 * phone the data is ABOUT. Both members of a pair can read it; the rules decide who can write.
 */
object PairRepository {
    private val auth get() = FirebaseAuth.getInstance()
    private val db get() = FirebaseFirestore.getInstance()
    private fun pairs() = db.collection("pairs")
    private fun phone(pairId: String, uid: String): DocumentReference =
        pairs().document(pairId).collection("phones").document(uid)
    private fun apps(pairId: String, uid: String) = phone(pairId, uid).collection("apps")

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
                "a" to uid,
                "b" to null,
                "createdAt" to FieldValue.serverTimestamp(),
            )
        ).await()
        code
    }

    suspend fun joinPair(rawCode: String): String = net {
        val code = rawCode.uppercase().filter { it in ALPHABET }
        require(code.length == CODE_LENGTH) { "Enter the $CODE_LENGTH-character code" }
        val uid = uid()
        val ref = pairs().document(code)
        // The rules deny reading a pair that two phones already share.
        val snap = try {
            ref.get().await()
        } catch (e: FirebaseFirestoreException) {
            if (e.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                error("That code is already used by two phones. Ask your friend for a new code.")
            }
            throw e
        }
        check(snap.exists()) { "No pairing found for that code" }
        val a = snap.getString("a")
        val b = snap.getString("b")
        check(a != uid || b != null) { "That is your own code. Enter the code from your friend's phone." }
        check(b == null || b == uid || a == uid) { "That code is already used by two phones" }
        if (b == null && a != uid) {
            try {
                ref.update("b", uid).await()
            } catch (e: FirebaseFirestoreException) {
                if (e.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                    error("The server refused the pairing (code already claimed?). Ask for a new code.")
                }
                throw e
            }
        }
        code
    }

    // ---------- live data ----------

    fun pairFlow(pairId: String): Flow<PairInfo?> = flow {
        uid()
        emitAll(rawPairFlow(pairId))
    }

    /** Apps locked ON [phoneUid]. */
    fun appsFlow(pairId: String, phoneUid: String): Flow<List<AppRule>> = flow {
        uid()
        emitAll(rawAppsFlow(pairId, phoneUid))
    }

    fun healthFlow(pairId: String, phoneUid: String): Flow<Health?> = flow {
        uid()
        emitAll(rawHealthFlow(pairId, phoneUid))
    }

    /** Newest 7 days of screen time for [phoneUid]. */
    fun usageFlow(pairId: String, phoneUid: String): Flow<List<UsageDay>> = flow {
        uid()
        emitAll(rawUsageFlow(pairId, phoneUid))
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

    private fun rawAppsFlow(pairId: String, phoneUid: String): Flow<List<AppRule>> = callbackFlow {
        val reg = apps(pairId, phoneUid).addSnapshotListener { snap, err ->
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

    private fun rawHealthFlow(pairId: String, phoneUid: String): Flow<Health?> = callbackFlow {
        val reg = phone(pairId, phoneUid).addSnapshotListener { snap, err ->
            if (err != null) {
                close(err)
                return@addSnapshotListener
            }
            trySend(snap?.toHealth())
        }
        awaitClose { reg.remove() }
    }

    private fun rawUsageFlow(pairId: String, phoneUid: String): Flow<List<UsageDay>> = callbackFlow {
        val reg = phone(pairId, phoneUid).collection("usage")
            // Range filter on the doc id (an ISO date). Needs no custom index, unlike orderBy+limit.
            .whereGreaterThanOrEqualTo(FieldPath.documentId(), LocalDate.now().minusDays(7).toString())
            .addSnapshotListener { snap, err ->
                if (err != null) {
                    close(err)
                    return@addSnapshotListener
                }
                if (snap != null) trySend(snap.documents.map { it.toUsageDay() })
            }
        awaitClose { reg.remove() }
    }

    // ---------- mutations ----------

    /** Owner adds a lock on their OWN phone. */
    suspend fun addOwnApp(pairId: String, ownUid: String, pkg: String, name: String) = net {
        apps(pairId, ownUid).document(pkg).set(
            mapOf("name" to name, "addedBy" to "self", "locked" to true, "unlockUntil" to null)
        ).await()
        true
    }

    /** Guardian: lock now (true) or release indefinitely (false). Clears any timed window. */
    suspend fun setLocked(pairId: String, phoneUid: String, pkg: String, locked: Boolean) = net {
        apps(pairId, phoneUid).document(pkg).update("locked", locked, "unlockUntil", null).await()
        true
    }

    /** Guardian: keep locked by default but open until [untilMillis]. */
    suspend fun unlockUntil(pairId: String, phoneUid: String, pkg: String, untilMillis: Long) = net {
        apps(pairId, phoneUid).document(pkg).update("locked", true, "unlockUntil", untilMillis).await()
        true
    }

    suspend fun removeApp(pairId: String, phoneUid: String, pkg: String) = net {
        apps(pairId, phoneUid).document(pkg).delete().await()
        true
    }

    suspend fun sendHeartbeat(pairId: String, s: Perms.Snapshot) = net {
        val uid = uid()
        phone(pairId, uid).set(
            mapOf(
                "lastHeartbeat" to FieldValue.serverTimestamp(),
                "accessibilityOn" to s.accessibility,
                "batteryExempt" to s.batteryExempt,
                "deviceAdmin" to s.deviceAdmin,
                "notificationsOn" to s.notifications,
                "usageAccess" to s.usageAccess,
                "hardened" to s.hardened,
            ),
            SetOptions.merge(),
        ).await()
        true
    }

    suspend fun pushUsage(pairId: String, day: UsageDay) = net {
        val uid = uid()
        phone(pairId, uid).collection("usage").document(day.date).set(
            mapOf(
                "total" to day.totalMs,
                "top" to day.apps.map { mapOf("pkg" to it.pkg, "name" to it.name, "ms" to it.ms) },
                "updatedAt" to FieldValue.serverTimestamp(),
            )
        ).await()
        true
    }

    // ---------- mapping ----------

    private fun DocumentSnapshot.toPair(): PairInfo? {
        if (!exists()) return null
        val creator = getString("a") ?: return null
        return PairInfo(id = id, creatorUid = creator, joinerUid = getString("b"))
    }

    private fun DocumentSnapshot.toHealth(): Health? {
        if (!exists()) return null
        return Health(
            lastHeartbeat = (get("lastHeartbeat") as? Timestamp)?.toDate()?.time,
            accessibilityOn = getBoolean("accessibilityOn") == true,
            batteryExempt = getBoolean("batteryExempt") == true,
            deviceAdmin = getBoolean("deviceAdmin") == true,
            notificationsOn = getBoolean("notificationsOn") == true,
            usageAccess = getBoolean("usageAccess") == true,
            hardened = getBoolean("hardened") == true,
        )
    }

    private fun DocumentSnapshot.toRule() = AppRule(
        pkg = id,
        name = getString("name") ?: id,
        locked = getBoolean("locked") ?: true, // fail closed
        unlockUntil = getLong("unlockUntil"),
        addedBy = getString("addedBy") ?: "",
    )

    private fun DocumentSnapshot.toUsageDay(): UsageDay {
        val list = get("top") as? List<*> ?: emptyList<Any>()
        val apps = list.mapNotNull { item ->
            @Suppress("UNCHECKED_CAST")
            val m = item as? Map<String, Any?> ?: return@mapNotNull null
            val pkg = m["pkg"] as? String ?: return@mapNotNull null
            AppUsage(pkg, m["name"] as? String ?: pkg, (m["ms"] as? Number)?.toLong() ?: 0L)
        }
        return UsageDay(date = id, totalMs = getLong("total") ?: 0L, apps = apps)
    }
}
