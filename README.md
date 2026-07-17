# Shared Control Impostor

Plugin Minecraft mini-game dimana semua pemain bermain **bergantian** mengontrol satu karakter di survival mode, dengan satu atau lebih **impostor** yang berusaha menggagalkan objektif.

---

## Requirement

- **Minecraft**: 1.21.11
- **Server**: Paper (API-version 1.21.11)
- **Java**: 21+

---

## Cara Install

1. Build plugin dengan Maven:
   ```bash
   mvn clean package
   ```
2. Copy file `.jar` dari folder `target/` ke folder `plugins/` server.
3. Restart server.

---

## Alur Permainan

1. **Registrasi pemain** dengan `/regis [player]` atau `/regisall`
2. **Konfigurasi** settings melalui item **Settings** di inventory
3. **Mulai game** dengan `/start`
4. Pemain bermain **bergantian** di survival world
5. Saat giliran habis, karakter berpindah ke pemain berikutnya beserta seluruh **state** (inventory, health, posisi, dll.)
6. Investigator harus menyelesaikan **objektif**, impostor harus mencegahnya
7. Jika diperlukan, panggil **meeting** dengan `/meeting` untuk voting eliminasi
8. Game berakhir jika semua objektif selesai (Investigator menang) atau impostor mendominasi (Impostor menang)

---

## Commands

| Command | Deskripsi | Permission |
|---------|-----------|------------|
| `/regis [player]` | Daftarkan pemain ke game | OP |
| `/regisall` | Daftarkan semua pemain online | OP |
| `/unregis [player]` | Hapus pemain dari daftar | OP |
| `/listplayer` | Lihat daftar pemain terdaftar | Semua pemain |
| `/start` | Mulai game | OP |
| `/endgame` | Akhiri game dan kembali ke lobby | OP |
| `/meeting` | Panggil meeting (voting) | Semua pemain |
| `/skip` | Lewati giliran aktif saat ini | OP |
| `/listobjective` | Lihat daftar objective custom | OP |
| `/editobjective <nomor>` | Edit custom objective pada mode own objective | OP |
| `/commandinfo` | Lihat info daftar command  | OP |

---

## Settings (Menu OP)

Klik kanan item **Settings** untuk membuka menu konfigurasi.

| Slot | Item | Fungsi |
|------|------|--------|
| 1 | Totem | Toggle **One Life** mode (mati = impostor menang) |
| 2 | Grass/Dirt Block | Toggle **Buat world baru** / **Lanjutkan world sebelumnya** |
| 3 | Book | Konfigurasi **Objective** (mode (one dan own), type (random, template, custom)) |
| 4 | Player Head | Konfigurasi **jumlah impostor** |
| 5 | Clock | Konfigurasi **Timer** (swap, voting, cooldown meeting) |

---

## Mode Objektif

### One Objective
Semua investigator berbagi 1 objektif yang sama.

### Own Objective
Setiap investigator mendapat objektif masing-masing. Jika seorang investigator dieliminasi, objektifnya menjadi objektif bersama yang bisa diselesaikan siapa saja.

NOTE : Hanya bisa melakukan objective yang ada pada scoreboard masing-masing, tidak bisa melakukan objective orang lain yang masih hidup.

---

## Tipe Objektif

| Tipe | Deskripsi |
|------|-----------|
| **Random** | Objektif dipilih secara acak dari pool template |
| **Template** | Pilih sendiri objektif dari daftar yang tersedia |
| **Custom** | Buat objektif custom dengan 4 input: Nama, Aksi (Mining/Pickup/Kill), Target (nama block/item/mob), dan Jumlah |

### Contoh Custom Objective
- Mining 32 Diamond Ore → Aksi: `Mining Block`, Target: `diamond_ore`, Jumlah: `32`
- Dapat 10 Arrow → Aksi: `Dapatkan Item`, Target: `arrow`, Jumlah: `10`
- Bunuh 3 Creeper → Aksi: `Bunuh Mob`, Target: `creeper`, Jumlah: `3`

> **Catatan**: Target menggunakan nama material/entity Minecraft (lowercase). Contoh: `diamond_ore`, `warden`, `iron_sword`.

---

## Objektif Template (One Objective)

| No | Objective |
|----|-----------|
| 1 | Masuk ke dimensi Nether |
| 2 | Dapatkan Ender Pearl dari Piglin |
| 3 | Dapatkan 1 Ghast Tear |
| 4 | Dapatkan 1 Ancient Debris |
| 5 | Dapatkan 1 Wither Skeleton Skull |
| 6 | Bunuh 5 Breeze |
| 7 | Bunuh Elder Guardian |
| 8 | Bunuh diri dengan Warden |
| 9 | Kalahkan Ender Dragon |
| 10 | Dapatkan Elytra |
| 11 | Jelajahi 10 biome berbeda |

## Objektif Template (Own Objective)

| No | Objective |
|----|-----------|
| 1 | Kumpulkan 32 Iron Ingot |
| 2 | Gunakan Enchanting Table |
| 3 | Tame Kucing atau Serigala |
| 4 | Dapatkan Bucket of Axolotl |
| 5 | Pukul Iron Golem |
| 6 | Tunggangi Horse sejauh 100 block |
| 7 | Makan Pufferfish |
| 8 | Capai ketinggian bedrock |
| 9 | MLG Water Bucket dari ketinggian 20 block |
| 10 | Makan 1 Golden Apple |
| 11 | Bunuh Chicken Jockey |
| 12 | Dapatkan Music Disc dari Creeper |
| 13 | Mining 64 stone |
| 14 | Makan Cake |
| 15 | Kumpulkan 5 warna Wool berbeda |
| 16 | Berlari sejauh 500 block |
| 17 | Pakai full set Iron Armor |
| 18 | Dapatkan honey bottle |
| 19 | Craft Diamond Pickaxe |
| 20 | Bunuh 10 Zombie |
| 21 | Nyalakan 5 TNT |
| 22 | Masuk ke dimensi Nether |


---

## Sistem World

- Saat `/start` dengan **Buat world baru** dipilih: survival world baru dibuat dan world lama dihapus
- Saat `/start` dengan **Lanjutkan world sebelumnya**: world survival terakhir dimuat kembali beserta posisi dan inventory pemain aktif terakhir
- `keepInventory` **true** secara default
- Spawpoint di **bed** berlaku untuk semua player

---

## Sistem Meeting

- Gunakan `/meeting` untuk memanggil voting
- Ada **cooldown** yang bisa dikonfigurasi via Settings
- Cooldown mulai berjalan setelah pemain aktif **bergerak** pertama kali setelah meeting selesai
- Setiap pemain hanya bisa memanggil **1 meeting**, sampai semua pemain yang tersisa sudah memanggil meeting

---

## Developer

- **Author**: czipo
- **API**: Paper 1.21.11