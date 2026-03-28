"use client";

import { useState } from "react";
import { usePlayers, useServices } from "@/lib/hooks";
import * as api from "@/lib/api";
import {
  Card,
  CardContent,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { toast } from "sonner";
import { Users, RefreshCw, ArrowRight } from "lucide-react";

export default function PlayersPage() {
  const { data: playerData, loading, refetch } = usePlayers();
  const { data: serviceData } = useServices();
  const [sendDialog, setSendDialog] = useState<{ player: string } | null>(null);
  const [targetService, setTargetService] = useState("");

  const players = playerData?.players ?? [];
  const services = serviceData?.services ?? [];

  async function handleSend() {
    if (!sendDialog || !targetService) return;
    try {
      await api.sendPlayer(sendDialog.player, targetService);
      toast.success(`${sendDialog.player} sent to ${targetService}`);
      setSendDialog(null);
      setTargetService("");
      refetch();
    } catch (e) {
      toast.error(`Failed: ${e instanceof Error ? e.message : "Unknown error"}`);
    }
  }

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold">Players</h1>
          <p className="text-muted-foreground">Connected players across all services</p>
        </div>
        <Button variant="outline" size="sm" onClick={refetch}>
          <RefreshCw className="mr-2 h-4 w-4" />
          Refresh
        </Button>
      </div>

      <Card>
        <CardHeader>
          <CardTitle className="flex items-center gap-2">
            <Users className="h-5 w-5" />
            Online Players ({players.length})
          </CardTitle>
        </CardHeader>
        <CardContent>
          {loading ? (
            <div className="space-y-2">
              {[...Array(5)].map((_, i) => (
                <div key={i} className="h-10 animate-pulse rounded bg-muted" />
              ))}
            </div>
          ) : players.length === 0 ? (
            <p className="text-sm text-muted-foreground py-8 text-center">
              No players online
            </p>
          ) : (
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>Player</TableHead>
                  <TableHead>Service</TableHead>
                  <TableHead className="w-[100px]">Actions</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {players.map((player) => (
                  <TableRow key={player.name}>
                    <TableCell className="font-medium">{player.name}</TableCell>
                    <TableCell>
                      <Badge variant="outline">{player.service}</Badge>
                    </TableCell>
                    <TableCell>
                      <Button
                        variant="ghost"
                        size="sm"
                        onClick={() => setSendDialog({ player: player.name })}
                      >
                        <ArrowRight className="mr-1 h-3 w-3" />
                        Send
                      </Button>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          )}
        </CardContent>
      </Card>

      <Dialog open={!!sendDialog} onOpenChange={() => setSendDialog(null)}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Send Player</DialogTitle>
            <DialogDescription>
              Transfer {sendDialog?.player} to another service
            </DialogDescription>
          </DialogHeader>
          <Select value={targetService} onValueChange={(v) => setTargetService(v ?? "")}>
            <SelectTrigger>
              <SelectValue placeholder="Select target service" />
            </SelectTrigger>
            <SelectContent>
              {services
                .filter((s) => s.state === "READY")
                .map((s) => (
                  <SelectItem key={s.name} value={s.name}>
                    {s.name}
                  </SelectItem>
                ))}
            </SelectContent>
          </Select>
          <DialogFooter>
            <Button variant="outline" onClick={() => setSendDialog(null)}>
              Cancel
            </Button>
            <Button onClick={handleSend} disabled={!targetService}>
              Send
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}
