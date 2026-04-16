/**
 * Central re-export of the lucide-react icons used across the dashboard.
 *
 * Call sites should import from `@/lib/icons` rather than `lucide-react`
 * directly, so any future library swap or per-icon tweak only touches
 * this one file. Some names are exported twice (e.g. `Server` and
 * `ServerIcon`, `FolderTree` and `FolderTreeIcon`) because the call sites
 * mix both spellings — they resolve to the same lucide component.
 *
 * Aliases handle a few renames between older lucide releases and the
 * shadcn/ui scaffolding still in use:
 *   - `Loader2`          → `LoaderCircle`
 *   - `MoreHorizontal`   → `Ellipsis`
 *   - `MoreVertical`     → `EllipsisVertical`
 */
"use client";

import {
  Activity,
  Archive,
  ArrowLeft,
  Box,
  CalendarClock,
  Container,
  CheckCircle2,
  ChevronDown,
  CircleAlert,
  CircleCheck,
  CircleDashed,
  CircleX,
  Clock,
  Cpu,
  Download,
  HardDrive,
  History,
  Ellipsis,
  EllipsisVertical,
  ExternalLink,
  File,
  Folder,
  FolderPlus,
  FolderTree,
  Gauge,
  Gavel,
  ImageIcon,
  LayoutDashboard,
  LoaderCircle,
  LogOut,
  MemoryStick,
  Monitor,
  Network,
  Package,
  PackageSearch,
  Pencil,
  Play,
  Plug,
  Plus,
  Radio,
  RefreshCw,
  RotateCw,
  Save,
  ScrollText,
  Search,
  Send,
  Server,
  Shield,
  Signpost,
  Square,
  Stethoscope,
  Terminal,
  Trash2,
  Upload,
  UserCheck,
  Users,
  X,
  Zap,
  ZoomIn,
  type LucideIcon,
} from "lucide-react";

// Re-export the lucide `LucideIcon` type so call sites that import it from
// here keep working without having to also import from lucide-react.
export type { LucideIcon };

// ── Icon exports ────────────────────────────────────────────────
// Name on the left is the public API exposed by this module. Where the
// codebase uses both a plain and an "Icon"-suffixed alias, we export both
// pointing at the same lucide component.

export {
  Activity,
  Archive,
  ArrowLeft,
  CalendarClock,
  CheckCircle2,
  ChevronDown,
  CircleAlert,
  CircleCheck,
  CircleDashed,
  CircleX,
  Clock,
  Container,
  Cpu,
  Download,
  File,
  Folder,
  FolderPlus,
  FolderTree,
  HardDrive,
  History,
  Gauge,
  Gavel,
  ImageIcon,
  MemoryStick,
  Network,
  Package,
  PackageSearch,
  Pencil,
  Play,
  Plug,
  Plus,
  Radio,
  RefreshCw,
  RotateCw,
  Save,
  ScrollText,
  Search,
  Send,
  Server,
  Shield,
  Signpost,
  Square,
  Stethoscope,
  Trash2,
  Upload,
  UserCheck,
  Users,
  X,
  ZoomIn,
};

// Aliases for names used at call sites.
export const BoxIcon = Box;
export const ContainerIcon = Container;
export const ChevronDownIcon = ChevronDown;
export const EllipsisVerticalIcon = EllipsisVertical;
export const ExternalLinkIcon = ExternalLink;
export const FolderTreeIcon = FolderTree;
export const GavelIcon = Gavel;
export const LayoutDashboardIcon = LayoutDashboard;
export const PackageIcon = Package;
export const Loader2 = LoaderCircle;
export const LogOutIcon = LogOut;
export const MonitorIcon = Monitor;
export const MoreHorizontal = Ellipsis;
export const MoreVertical = EllipsisVertical;
export const NetworkIcon = Network;
export const PlugIcon = Plug;
export const RadioIcon = Radio;
export const ScrollTextIcon = ScrollText;
export const ServerIcon = Server;
export const StethoscopeIcon = Stethoscope;
export const ShieldIcon = Shield;
export const TerminalIcon = Terminal;
export const UsersIcon = Users;
export const ZapIcon = Zap;
