package com.swisscom.health.des.cdr.client

import com.sun.jna.Platform
import com.sun.jna.platform.win32.Advapi32Util
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.file.AccessDeniedException
import java.nio.file.FileSystem
import java.nio.file.Path
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.UserPrincipal
import java.nio.file.spi.FileSystemProvider

class ConfigInitTest {
    private val configFile = mockk<Path>()
    private val fileSystem = mockk<FileSystem>()
    private val provider = mockk<FileSystemProvider>()
    private val aclView = mockk<AclFileAttributeView>()
    private lateinit var remainingEntries: List<AclEntry>

    @BeforeEach
    fun setUp() {
        Platform.isWindows() // initialize JNA before mocking; otherwise mockk records its static initializer calls
        mockkStatic(Platform::class)
        mockkStatic(Advapi32Util::class)
        mockkObject(ConfigInit)
        every { Platform.isWindows() } returns true
        every { configFile.fileSystem } returns fileSystem
        every { fileSystem.provider() } returns provider
        every { provider.getFileAttributeView(configFile, AclFileAttributeView::class.java) } returns aclView
        every { aclView.acl = any() } answers { remainingEntries = firstArg() }
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `SYSTEM service user filters ACLs by well-known SIDs without resolving other accounts`() {
        every { Advapi32Util.getAccountBySid("S-1-5-18") } returns Advapi32Util.Account().apply { fqn = "NT AUTHORITY\\SYSTEM" }
        every { Advapi32Util.getAccountBySid("S-1-5-32-544") } returns Advapi32Util.Account().apply { fqn = "BUILTIN\\Administrators" }
        every { ConfigInit.currentWindowsUserSid() } returns "S-1-5-18"
        val system = entry("NT AUTHORITY\\SYSTEM")
        val administrators = entry("BUILTIN\\Administrators")
        every { aclView.acl } answers {
            if (::remainingEntries.isInitialized) remainingEntries else listOf(system, administrators, entry("CONTOSO\\user"))
        }

        assertEquals(configFile, removeWorldAccess())

        assertEquals(listOf(system, administrators), remainingEntries)

        verify(exactly = 0) { Advapi32Util.getAccountByName(any<String>()) }
    }

    @Test
    fun `custom service account retains its existing ACL entry`() {
        val serviceSid = "S-1-5-21-1-2-3-1001"
        every { ConfigInit.currentWindowsUserSid() } returns serviceSid
        every { Advapi32Util.getAccountByName("NT AUTHORITY\\SYSTEM") } returns Advapi32Util.Account().apply { sidString = "S-1-5-18" }
        every { Advapi32Util.getAccountByName("CONTOSO\\service") } returns Advapi32Util.Account().apply { sidString = serviceSid }
        every { Advapi32Util.getAccountByName("CONTOSO\\user") } returns Advapi32Util.Account().apply { sidString = "S-1-5-21-1-2-3-1002" }
        val system = entry("NT AUTHORITY\\SYSTEM")
        val service = entry("CONTOSO\\service")
        every { aclView.acl } answers {
            if (::remainingEntries.isInitialized) remainingEntries else listOf(system, service, entry("CONTOSO\\user"))
        }

        removeWorldAccess()

        assertEquals(listOf(system, service), remainingEntries)
    }

    @Test
    fun `unresolvable account leaves ACLs untouched`() {
        every { ConfigInit.currentWindowsUserSid() } returns "S-1-5-21-1-2-3-1001"
        every { Advapi32Util.getAccountByName("CONTOSO\\ghost") } throws IllegalStateException("account not found")
        every { Advapi32Util.getAccountByName("NT AUTHORITY\\SYSTEM") } returns Advapi32Util.Account().apply { sidString = "S-1-5-18" }
        every { aclView.acl } returns listOf(entry("NT AUTHORITY\\SYSTEM"), entry("CONTOSO\\ghost"))

        assertEquals(configFile, removeWorldAccess())

        verify(exactly = 0) { aclView.acl = any() }
    }

    @Test
    fun `SYSTEM service user leaves ACLs untouched if well-known SIDs cannot be resolved`() {
        every { ConfigInit.currentWindowsUserSid() } returns "S-1-5-18"
        every { Advapi32Util.getAccountBySid("S-1-5-18") } throws IllegalStateException("lookup failed")
        every { Advapi32Util.getAccountBySid("S-1-5-32-544") } returns Advapi32Util.Account().apply { fqn = "BUILTIN\\Administrators" }
        every { aclView.acl } returns listOf(entry("NT AUTHORITY\\SYSTEM"))

        removeWorldAccess()

        verify(exactly = 0) { aclView.acl = any() }
    }

    @Test
    fun `unknown identity leaves ACLs untouched and allows initialization`() {
        every { ConfigInit.currentWindowsUserSid() } returns null

        assertEquals(configFile, removeWorldAccess())

        verify(exactly = 0) { provider.getFileAttributeView(configFile, AclFileAttributeView::class.java) }
        verify(exactly = 0) { aclView.acl = any() }
    }

    @Test
    fun `ACL write failure still propagates`() {
        every { ConfigInit.currentWindowsUserSid() } returns "S-1-5-18"
        every { Advapi32Util.getAccountBySid("S-1-5-18") } returns Advapi32Util.Account().apply { fqn = "NT AUTHORITY\\SYSTEM" }
        every { Advapi32Util.getAccountBySid("S-1-5-32-544") } returns Advapi32Util.Account().apply { fqn = "BUILTIN\\Administrators" }
        every { aclView.acl } returns listOf(entry("NT AUTHORITY\\SYSTEM"))
        every { aclView.acl = any() } throws AccessDeniedException(configFile.toString())

        assertThrows<AccessDeniedException> { removeWorldAccess() }
    }

    private fun removeWorldAccess(): Path = with(ConfigInit) { configFile.removeWorldAccess() }

    private fun entry(name: String): AclEntry =
        AclEntry.newBuilder()
            .setType(AclEntryType.ALLOW)
            .setPrincipal(mockk<UserPrincipal> { every { getName() } returns name })
            .setPermissions(AclEntryPermission.READ_DATA)
            .build()
}
