/*
 * Copyright (c) 2016, 2017, 2018, 2019 FabricMC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.fabricmc.stitch.merge;

import net.fabricmc.stitch.util.StitchUtil;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

import java.util.*;

public class ClassMerger {
    private static final String CLIENT_ONLY_DESCRIPTOR = "Lorg/muonmc/loader/api/game/minecraft/ClientOnly;";
    private static final String DEDICATED_SERVER_ONLY_DESCRIPTOR = "Lorg/muonmc/loader/api/game/minecraft/DedicatedServerOnly;";

    private abstract class Merger<T> {
        private final Map<String, T> entriesClient, entriesServer;
        private final List<String> entryNames;

        public Merger(List<T> entriesClient, List<T> entriesServer) {
            this.entriesClient = new LinkedHashMap<>();
            this.entriesServer = new LinkedHashMap<>();

            List<String> listClient = toMap(entriesClient, this.entriesClient);
            List<String> listServer = toMap(entriesServer, this.entriesServer);

            this.entryNames = StitchUtil.mergePreserveOrder(listClient, listServer);
        }

        public abstract String getName(T entry);
        public abstract void applySide(T entry, String side);

        private List<String> toMap(List<T> entries, Map<String, T> map) {
            List<String> list = new ArrayList<>(entries.size());
            for (T entry : entries) {
                String name = getName(entry);
                map.put(name, entry);
                list.add(name);
            }
            return list;
        }

        public void merge(List<T> list) {
            for (String s : entryNames) {
                T entryClient = entriesClient.get(s);
                T entryServer = entriesServer.get(s);

                if (entryClient != null && entryServer != null) {
                    list.add(entryClient);
                } else if (entryClient != null) {
                    applySide(entryClient, "CLIENT");
                    list.add(entryClient);
                } else {
                    applySide(entryServer, "SERVER");
                    list.add(entryServer);
                }
            }
        }
    }

    private static String getSidedDescriptor(String side) {
        switch (side) {
            case "CLIENT":
                return CLIENT_ONLY_DESCRIPTOR;
            case "SERVER":
                return DEDICATED_SERVER_ONLY_DESCRIPTOR;
            default:
                throw new RuntimeException("Invalid side " + side);
        }
    }

    private static void visitItfAnnotation(AnnotationVisitor av, String side, List<String> itfDescriptors) {
        for (String ignored : itfDescriptors) {
            AnnotationVisitor avItf = av.visitAnnotation(null, getSidedDescriptor(side));
            avItf.visitEnd();
        }
    }

    public static class SidedClassVisitor extends ClassVisitor {
        private final String side;

        public SidedClassVisitor(int api, ClassVisitor cv, String side) {
            super(api, cv);
            this.side = side;
        }

        @Override
        public void visitEnd() {
            cv.visitAnnotation(getSidedDescriptor(side), true);
            super.visitEnd();
        }
    }

    public ClassMerger() {

    }

    public byte[] merge(byte[] classClient, byte[] classServer) {
        ClassReader readerC = new ClassReader(classClient);
        ClassReader readerS = new ClassReader(classServer);
        ClassWriter writer = new ClassWriter(0);

        ClassNode nodeC = new ClassNode(StitchUtil.ASM_VERSION);
        readerC.accept(nodeC, 0);

        ClassNode nodeS = new ClassNode(StitchUtil.ASM_VERSION);
        readerS.accept(nodeS, 0);

        ClassNode nodeOut = new ClassNode(StitchUtil.ASM_VERSION);
        nodeOut.version = nodeC.version;
        nodeOut.access = nodeC.access;
        nodeOut.name = nodeC.name;
        nodeOut.signature = nodeC.signature;
        nodeOut.superName = nodeC.superName;
        nodeOut.sourceFile = nodeC.sourceFile;
        nodeOut.sourceDebug = nodeC.sourceDebug;
        nodeOut.outerClass = nodeC.outerClass;
        nodeOut.outerMethod = nodeC.outerMethod;
        nodeOut.outerMethodDesc = nodeC.outerMethodDesc;
        nodeOut.module = nodeC.module;
        nodeOut.nestHostClass = nodeC.nestHostClass;
        nodeOut.nestMembers = nodeC.nestMembers;
        nodeOut.attrs = nodeC.attrs;

        if (nodeC.invisibleAnnotations != null) {
            nodeOut.invisibleAnnotations = new ArrayList<>();
            nodeOut.invisibleAnnotations.addAll(nodeC.invisibleAnnotations);
        }
        if (nodeC.invisibleTypeAnnotations != null) {
            nodeOut.invisibleTypeAnnotations = new ArrayList<>();
            nodeOut.invisibleTypeAnnotations.addAll(nodeC.invisibleTypeAnnotations);
        }
        if (nodeC.visibleAnnotations != null) {
            nodeOut.visibleAnnotations = new ArrayList<>();
            nodeOut.visibleAnnotations.addAll(nodeC.visibleAnnotations);
        }
        if (nodeC.visibleTypeAnnotations != null) {
            nodeOut.visibleTypeAnnotations = new ArrayList<>();
            nodeOut.visibleTypeAnnotations.addAll(nodeC.visibleTypeAnnotations);
        }

        List<String> itfs = StitchUtil.mergePreserveOrder(nodeC.interfaces, nodeS.interfaces);
        nodeOut.interfaces = new ArrayList<>();

        List<String> clientItfs = new ArrayList<>();
        List<String> serverItfs = new ArrayList<>();

        for (String s : itfs) {
            boolean nc = nodeC.interfaces.contains(s);
            boolean ns = nodeS.interfaces.contains(s);
            nodeOut.interfaces.add(s);
            if (nc && !ns) {
                clientItfs.add(s);
            } else if (ns && !nc) {
                serverItfs.add(s);
            }
        }

        if (!clientItfs.isEmpty() || !serverItfs.isEmpty()) {
            if (!clientItfs.isEmpty()) {
                // do nothing
            }
            if (!serverItfs.isEmpty()) {
                // do nothing
            }
        }

        new Merger<InnerClassNode>(nodeC.innerClasses, nodeS.innerClasses) {
            @Override
            public String getName(InnerClassNode entry) {
                return entry.name;
            }

            @Override
            public void applySide(InnerClassNode entry, String side) {
            }
        }.merge(nodeOut.innerClasses);

        new Merger<FieldNode>(nodeC.fields, nodeS.fields) {
            @Override
            public String getName(FieldNode entry) {
                return entry.name + ";;" + entry.desc;
            }

            @Override
            public void applySide(FieldNode entry, String side) {
                entry.visitAnnotation(getSidedDescriptor(side), true);
            }
        }.merge(nodeOut.fields);

        new Merger<MethodNode>(nodeC.methods, nodeS.methods) {
            @Override
            public String getName(MethodNode entry) {
                return entry.name + entry.desc;
            }

            @Override
            public void applySide(MethodNode entry, String side) {
                entry.visitAnnotation(getSidedDescriptor(side), true);
            }
        }.merge(nodeOut.methods);

        nodeOut.accept(writer);
        return writer.toByteArray();
    }
}
