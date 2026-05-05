import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.*;
import javax.imageio.ImageIO;

public class JAVA_0505_Assigaments_1 {

    static class Blob implements Comparable<Blob> {
        int id, area;
        public Blob(int id, int area) { this.id = id; this.area = area; }
        @Override
        public int compareTo(Blob other) { return Integer.compare(other.area, this.area); } // 面積降序排列
    }

    public static void main(String[] args) {
        String inputPath = "input.png";
        File inputFile = new File(inputPath);
        if (!inputFile.exists()) {
            inputPath = "input.jpg";
            inputFile = new File(inputPath);
        }

        try {
            BufferedImage image = ImageIO.read(inputFile);
            if (image == null) return;

            int w = image.getWidth();
            int h = image.getHeight();
            int totalPixels = w * h;

            // 1. 轉灰階與直方圖[cite: 1]
            int[] histogram = new int[256];
            int[][] grayMap = new int[w][h];
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int rgb = image.getRGB(x, y);
                    int gray = (int) (0.299 * ((rgb >> 16) & 0xFF) + 0.587 * ((rgb >> 8) & 0xFF) + 0.114 * (rgb & 0xFF));
                    grayMap[x][y] = gray;
                    histogram[gray]++;
                }
            }

            // 2. Multi-Otsu 尋找門檻值 T1, T2[cite: 1]
            double[] P = new double[256], S = new double[256];
            P[0] = (double) histogram[0] / totalPixels;
            S[0] = 0;
            for (int i = 1; i < 256; i++) {
                double prob = (double) histogram[i] / totalPixels;
                P[i] = P[i - 1] + prob;
                S[i] = S[i - 1] + i * prob;
            }

            int optT1 = 0, optT2 = 0;
            double maxVar = -1.0;
            for (int t1 = 0; t1 < 254; t1++) {
                for (int t2 = t1 + 1; t2 < 255; t2++) {
                    double w0 = P[t1], w1 = P[t2] - P[t1], w2 = P[255] - P[t2];
                    if (w0 <= 0 || w1 <= 0 || w2 <= 0) continue;
                    double m0 = S[t1] / w0, m1 = (S[t2] - S[t1]) / w1, m2 = (S[255] - S[t2]) / w2;
                    double varBetween = w0 * m0 * m0 + w1 * m1 * m1 + w2 * m2 * m2;
                    if (varBetween > maxVar) { maxVar = varBetween; optT1 = t1; optT2 = t2; }
                }
            }

            // 3. 建立基礎遮罩：這次放寬標準，<= T2 都當前景，這樣就能抓到 Z fc 的銀色機身
            int[][] mask = new int[w][h];
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    mask[x][y] = (grayMap[x][y] <= optT2) ? 1 : 0;
                }
            }

            // 4. 高效填補破洞 (從邊界向內 BFS)
            int[][] filledMask = fillHoles(mask, w, h);

            // 5. 形態學侵蝕 (Erosion) 10 次，切斷底部陰影連結
            int[][] erodedMask = erode(filledMask, w, h, 10);

            // 6. 在侵蝕後的遮罩中尋找相機核心 (CCL)
            int[][] coreLabels = new int[w][h];
            int coreCount = 0;
            List<Blob> coreBlobs = new ArrayList<>();
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    if (erodedMask[x][y] == 1 && coreLabels[x][y] == 0) {
                        coreCount++;
                        int area = bfsArea(erodedMask, coreLabels, x, y, coreCount, w, h, 1);
                        coreBlobs.add(new Blob(coreCount, area));
                    }
                }
            }
            Collections.sort(coreBlobs); // 找出最大的前三個核心

            // 7. 種子區域生長 (Seeded Region Growing) - 讓相機長回原本大小但彼此不沾黏
            int[][] finalLabels = new int[w][h];
            Queue<int[]> queue = new LinkedList<>();
            
            // 把前三大核心放進佇列當作種子
            for(int y=0; y<h; y++) {
                for(int x=0; x<w; x++) {
                    for(int i=0; i<Math.min(3, coreBlobs.size()); i++) {
                        if(coreLabels[x][y] == coreBlobs.get(i).id) {
                            finalLabels[x][y] = i + 1; // 給予標籤 1, 2, 3
                            queue.add(new int[]{x, y});
                            break;
                        }
                    }
                }
            }

            // 開始生長：只能長在 original filledMask == 1 的地方
            int[] dx = {0, 0, 1, -1};
            int[] dy = {1, -1, 0, 0};
            while(!queue.isEmpty()) {
                int[] p = queue.poll();
                int currentLabel = finalLabels[p[0]][p[1]];
                for(int i=0; i<4; i++) {
                    int nx = p[0] + dx[i], ny = p[1] + dy[i];
                    if(nx >= 0 && nx < w && ny >= 0 && ny < h) {
                        if(filledMask[nx][ny] == 1 && finalLabels[nx][ny] == 0) {
                            finalLabels[nx][ny] = currentLabel;
                            queue.add(new int[]{nx, ny});
                        }
                    }
                }
            }

            // 8. 上色輸出
            int colorBg = new Color(245, 245, 250).getRGB(); 
            int[] topColors = {
                new Color(255, 80, 80).getRGB(),   // Z 8 (紅)
                new Color(80, 200, 80).getRGB(),   // D850 (綠)
                new Color(80, 80, 255).getRGB()    // Z fc (藍)
            };

            BufferedImage output = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int label = finalLabels[x][y];
                    if (label >= 1 && label <= 3) {
                        output.setRGB(x, y, topColors[label - 1]);
                    } else {
                        output.setRGB(x, y, colorBg);
                    }
                }
            }

            ImageIO.write(output, "png", new File("output.png"));
            System.out.println("Z fc 救援成功！三臺相機已完美獨立分割！");

        } catch (IOException e) { e.printStackTrace(); }
    }

    // 邊界填補破洞演算法
    private static int[][] fillHoles(int[][] mask, int w, int h) {
        int[][] filled = new int[w][h];
        for(int x=0; x<w; x++) for(int y=0; y<h; y++) filled[x][y] = 1; 
        Queue<int[]> q = new LinkedList<>();
        for(int x=0; x<w; x++) {
            if(mask[x][0] == 0) { q.add(new int[]{x,0}); filled[x][0] = 0; }
            if(mask[x][h-1] == 0) { q.add(new int[]{x,h-1}); filled[x][h-1] = 0; }
        }
        for(int y=0; y<h; y++) {
            if(mask[0][y] == 0) { q.add(new int[]{0,y}); filled[0][y] = 0; }
            if(mask[w-1][y] == 0) { q.add(new int[]{w-1,y}); filled[w-1][y] = 0; }
        }
        int[] dx = {0,0,1,-1}, dy = {1,-1,0,0};
        while(!q.isEmpty()) {
            int[] p = q.poll();
            for(int i=0; i<4; i++) {
                int nx = p[0]+dx[i], ny = p[1]+dy[i];
                if(nx>=0 && nx<w && ny>=0 && ny<h && mask[nx][ny] == 0 && filled[nx][ny] == 1) {
                    filled[nx][ny] = 0; q.add(new int[]{nx,ny});
                }
            }
        }
        return filled;
    }

    // 形態學侵蝕
    private static int[][] erode(int[][] input, int w, int h, int iters) {
        int[][] curr = input;
        int[] dx = {0,0,1,-1}, dy = {1,-1,0,0};
        for(int i=0; i<iters; i++) {
            int[][] next = new int[w][h];
            for(int y=0; y<h; y++) {
                for(int x=0; x<w; x++) {
                    if(curr[x][y] == 1) {
                        boolean keep = true;
                        for(int d=0; d<4; d++) {
                            int nx = x+dx[d], ny = y+dy[d];
                            if(nx>=0 && nx<w && ny>=0 && ny<h) {
                                if(curr[nx][ny] == 0) { keep = false; break; }
                            } else { keep = false; }
                        }
                        next[x][y] = keep ? 1 : 0;
                    }
                }
            }
            curr = next;
        }
        return curr;
    }

    // CCL 面積計算
    private static int bfsArea(int[][] mask, int[][] labels, int startX, int startY, int labelId, int w, int h, int target) {
        Queue<int[]> q = new LinkedList<>();
        q.add(new int[]{startX, startY});
        labels[startX][startY] = labelId;
        int area = 0;
        int[] dx = {0,0,1,-1}, dy = {1,-1,0,0};
        while(!q.isEmpty()) {
            int[] p = q.poll(); area++;
            for(int i=0; i<4; i++) {
                int nx = p[0]+dx[i], ny = p[1]+dy[i];
                if(nx>=0 && nx<w && ny>=0 && ny<h && mask[nx][ny] == target && labels[nx][ny] == 0) {
                    labels[nx][ny] = labelId; q.add(new int[]{nx,ny});
                }
            }
        }
        return area;
    }
}