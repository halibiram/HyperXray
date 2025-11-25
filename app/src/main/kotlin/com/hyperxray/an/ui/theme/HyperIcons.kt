package com.hyperxray.an.ui.theme

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Centralized Icon Design System for HyperXray
 * 
 * This object serves as the single source of truth for all app icons.
 * Icons are organized by function/semantic meaning rather than visual name.
 * 
 * Design Philosophy:
 * - All icons use Icons.Rounded for a modern, premium, futuristic look
 * - Icons are named by their function (e.g., Dashboard, Connect) not appearance
 * - Easy to swap icon styles globally by changing Icons.Rounded to Icons.Outlined/Filled
 * 
 * Usage:
 * ```kotlin
 * Icon(
 *     imageVector = HyperIcons.Dashboard,
 *     contentDescription = "Dashboard"
 * )
 * ```
 */
object HyperIcons {
    // ========== Navigation Icons ==========
    /** Main dashboard/home screen icon */
    val Dashboard: ImageVector = Icons.Rounded.Dashboard
    
    /** Configuration/profiles screen icon */
    val Configs: ImageVector = Icons.Rounded.Code
    
    /** Logs/history screen icon */
    val Logs: ImageVector = Icons.Rounded.History
    
    /** Settings/preferences screen icon */
    val Settings: ImageVector = Icons.Rounded.Settings
    
    /** Routing rules screen icon */
    val Routing: ImageVector = Icons.Rounded.Route
    
    /** Utils/tools screen icon */
    val Utils: ImageVector = Icons.Rounded.Build
    
    /** Back navigation icon */
    @Suppress("DEPRECATION")
    val Back: ImageVector = Icons.Rounded.ArrowBack
    
    /** Forward navigation icon */
    @Suppress("DEPRECATION")
    val Forward: ImageVector = Icons.Rounded.ArrowForward
    
    /** Up arrow icon */
    val ArrowUp: ImageVector = Icons.Rounded.KeyboardArrowUp
    
    /** Down arrow icon */
    val ArrowDown: ImageVector = Icons.Rounded.KeyboardArrowDown
    
    /** Dropdown arrow icon */
    val Dropdown: ImageVector = Icons.Rounded.ArrowDropDown
    
    // ========== Action Icons ==========
    /** Connect/Start action icon */
    val Connect: ImageVector = Icons.Rounded.PlayArrow
    
    /** Disconnect/Stop action icon */
    val Disconnect: ImageVector = Icons.Rounded.Stop
    
    /** Pause action icon */
    val Pause: ImageVector = Icons.Rounded.Pause
    
    /** Edit/Modify action icon */
    val Edit: ImageVector = Icons.Rounded.Edit
    
    /** Save action icon */
    val Save: ImageVector = Icons.Rounded.Save
    
    /** Delete/Remove action icon */
    val Delete: ImageVector = Icons.Rounded.Delete
    
    /** Share action icon */
    val Share: ImageVector = Icons.Rounded.Share
    
    /** Copy action icon */
    val Copy: ImageVector = Icons.Rounded.ContentCopy
    
    /** Refresh/Reload action icon */
    val Refresh: ImageVector = Icons.Rounded.Refresh
    
    /** Search action icon */
    val Search: ImageVector = Icons.Rounded.Search
    
    /** Clear/Close action icon */
    val Clear: ImageVector = Icons.Rounded.Close
    
    /** Send action icon */
    @Suppress("DEPRECATION")
    val Send: ImageVector = Icons.Rounded.Send
    
    /** More options menu icon */
    val MoreVert: ImageVector = Icons.Rounded.MoreVert
    
    /** More options horizontal icon */
    val MoreHoriz: ImageVector = Icons.Rounded.MoreHoriz
    
    /** Add/Create new icon */
    val Add: ImageVector = Icons.Rounded.Add
    
    /** Check/Confirm icon */
    val Check: ImageVector = Icons.Rounded.Check
    
    /** Check circle icon */
    val CheckCircle: ImageVector = Icons.Rounded.CheckCircle
    
    // ========== Network & VPN Icons ==========
    /** VPN key/security icon */
    val VpnKey: ImageVector = Icons.Rounded.VpnKey
    
    /** Security/Shield icon */
    val Security: ImageVector = Icons.Rounded.Security
    
    /** Speed/Performance icon */
    val Speed: ImageVector = Icons.Rounded.Speed
    
    /** Rocket launch icon (for speed/performance) */
    val RocketLaunch: ImageVector = Icons.Rounded.RocketLaunch
    
    /** Ping/Latency icon */
    val Ping: ImageVector = Icons.Rounded.NetworkCheck
    
    /** Upload icon */
    val Upload: ImageVector = Icons.Rounded.Upload
    
    /** Download icon */
    val Download: ImageVector = Icons.Rounded.Download
    
    /** Cloud download icon */
    val CloudDownload: ImageVector = Icons.Rounded.CloudDownload
    
    /** DNS icon */
    val Dns: ImageVector = Icons.Rounded.Dns
    
    /** Firewall icon */
    val Firewall: ImageVector = Icons.Rounded.Security
    
    /** Network/WiFi icon */
    val Network: ImageVector = Icons.Rounded.Wifi
    
    /** Network check icon */
    val NetworkCheck: ImageVector = Icons.Rounded.NetworkCheck
    
    /** Public/Internet icon */
    val Public: ImageVector = Icons.Rounded.Public
    
    /** Lock/Secure icon */
    val Lock: ImageVector = Icons.Rounded.Lock
    
    /** Unlock icon */
    val Unlock: ImageVector = Icons.Rounded.LockOpen
    
    /** List/View icon */
    @Suppress("DEPRECATION")
    val List: ImageVector = Icons.Rounded.List
    
    // ========== Status Icons ==========
    /** Success/Check circle icon */
    val Success: ImageVector = Icons.Rounded.CheckCircle
    
    /** Error/Warning circle icon */
    val Error: ImageVector = Icons.Rounded.Error
    
    /** Warning icon */
    val Warning: ImageVector = Icons.Rounded.Warning
    
    /** Info icon */
    val Info: ImageVector = Icons.Rounded.Info
    
    /** Error outline icon */
    val ErrorOutline: ImageVector = Icons.Rounded.ErrorOutline
    
    /** Warning outline icon */
    val WarningAmber: ImageVector = Icons.Rounded.WarningAmber
    
    // ========== Additional Utility Icons ==========
    /** Filter icon */
    val Filter: ImageVector = Icons.Rounded.FilterList
    
    /** Sort icon */
    @Suppress("DEPRECATION")
    val Sort: ImageVector = Icons.Rounded.Sort
    
    /** Expand more icon */
    val ExpandMore: ImageVector = Icons.Rounded.ExpandMore
    
    /** Expand less icon */
    val ExpandLess: ImageVector = Icons.Rounded.ExpandLess
    
    /** Visibility/Show icon */
    val Visibility: ImageVector = Icons.Rounded.Visibility
    
    /** Visibility off/Hide icon */
    val VisibilityOff: ImageVector = Icons.Rounded.VisibilityOff
    
    /** Menu/Hamburger icon */
    val Menu: ImageVector = Icons.Rounded.Menu
    
    /** Home icon */
    val Home: ImageVector = Icons.Rounded.Home
    
    /** Folder icon */
    val Folder: ImageVector = Icons.Rounded.Folder
    
    /** File icon */
    @Suppress("DEPRECATION")
    val File: ImageVector = Icons.Rounded.InsertDriveFile
    
    /** Code/Developer icon */
    val Code: ImageVector = Icons.Rounded.Code
    
    /** History icon */
    val History: ImageVector = Icons.Rounded.History
    
    /** Analytics/Stats icon */
    val Analytics: ImageVector = Icons.Rounded.Analytics
    
    /** Tune/Settings adjust icon */
    val Tune: ImageVector = Icons.Rounded.Tune
}

