module jforge.processor {
    requires com.google.auto.service;
    requires com.palantir.javapoet;
    requires java.compiler;
    requires org.jspecify;
    requires jforge.annotation;

    provides javax.annotation.processing.Processor with io.github.erdsgfc.jforge.processor.JForgeProcessor;
}