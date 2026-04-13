/**
 * Drop-in Phosphor Icons replacement for the lucide-react icons used across
 * the dashboard. Each export keeps the ORIGINAL lucide name so call sites only
 * need an import-path swap:
 *
 *   -import { Server, Clock } from "lucide-react"
 *   +import { Server, Clock } from "@/lib/icons"
 *
 * All icons use Phosphor's "regular" weight (the default) — the rounded,
 * friendly look. Phosphor icons are already React components that accept
 * `className`, `size`, `weight`, `color` and standard SVG props, so we just
 * re-export them under the names the rest of the app expects.
 */
"use client";

import type { Icon as PhosphorIconComponent } from "@phosphor-icons/react";
import {
  Archive as PhArchive,
  ArrowClockwise,
  ArrowLeft as PhArrowLeft,
  ArrowSquareOut,
  ArrowsClockwise,
  Bell as PhBell,
  Broadcast,
  CaretDown,
  CircleNotch,
  Clock as PhClock,
  Cpu as PhCpu,
  Cube,
  DotsThree,
  DotsThreeVertical,
  DownloadSimple,
  FloppyDisk,
  Gauge as PhGauge,
  HardDrives,
  Lightning,
  MagnifyingGlass,
  Memory,
  Monitor,
  Network as PhNetwork,
  Package as PhPackage,
  PaperPlaneTilt,
  Pencil as PhPencil,
  Play as PhPlay,
  Plug as PhPlug,
  Plus as PhPlus,
  Pulse,
  Shield as PhShield,
  SignOut,
  Signpost as PhSignpost,
  SquaresFour,
  Square as PhSquare,
  Scroll,
  Terminal,
  Trash,
  TreeStructure,
  UploadSimple,
  UserCheck as PhUserCheck,
  Users as PhUsers,
  X as PhX,
} from "@phosphor-icons/react";

// Phosphor ships its own `Icon` type that accepts className/size/weight/color
// and standard SVG attrs. We re-export it under the lucide alias so any
// code that used `type LucideIcon` keeps working without changes.
export type LucideIcon = PhosphorIconComponent;

// ── Mapped exports ──────────────────────────────────────────────
// Name on the left = the lucide import name used across the dashboard.
// Name on the right = the matching Phosphor icon. Where lucide had both a
// "plain" and "Icon"-suffixed alias (e.g. `Server` and `ServerIcon`), we
// export both pointing at the same Phosphor component.

export const Activity = Pulse;
export const ActivityIcon = Pulse;
export const ArchiveIcon = PhArchive;
export const ArrowLeft = PhArrowLeft;
export const BellIcon = PhBell;
export const BoxIcon = Cube;
export const ChevronDownIcon = CaretDown;
export const Clock = PhClock;
export const Cpu = PhCpu;
export const Download = DownloadSimple;
export const EllipsisVerticalIcon = DotsThreeVertical;
export const ExternalLinkIcon = ArrowSquareOut;
export const FolderTree = TreeStructure;
export const FolderTreeIcon = TreeStructure;
export const Gauge = PhGauge;
export const LayoutDashboardIcon = SquaresFour;
export const Loader2 = CircleNotch;
export const LogOutIcon = SignOut;
export const MemoryStick = Memory;
export const MonitorIcon = Monitor;
export const MoreHorizontal = DotsThree;
export const MoreVertical = DotsThreeVertical;
export const Network = PhNetwork;
export const NetworkIcon = PhNetwork;
export const Package = PhPackage;
// Phosphor doesn't have a "PackageSearch" combo, but Package is close enough
// semantically for the only site that uses it (plugin installer).
export const PackageSearch = PhPackage;
export const Pencil = PhPencil;
export const Play = PhPlay;
export const Plug = PhPlug;
export const PlugIcon = PhPlug;
export const Plus = PhPlus;
export const RadioIcon = Broadcast;
export const RefreshCw = ArrowsClockwise;
export const RotateCw = ArrowClockwise;
export const Save = FloppyDisk;
export const ScrollText = Scroll;
export const ScrollTextIcon = Scroll;
export const Search = MagnifyingGlass;
export const Send = PaperPlaneTilt;
export const Server = HardDrives;
export const ServerIcon = HardDrives;
export const Shield = PhShield;
export const ShieldIcon = PhShield;
export const Signpost = PhSignpost;
export const Square = PhSquare;
export const TerminalIcon = Terminal;
export const Trash2 = Trash;
export const Upload = UploadSimple;
export const UserCheck = PhUserCheck;
export const Users = PhUsers;
export const UsersIcon = PhUsers;
export const X = PhX;
export const ZapIcon = Lightning;
