node {
    
    stage 'variables'

    sh "echo ${env.PWD}"
    sh "env"
    
    stage 'taking down containers'
    
    sh "docker inspect -f {{.State.Running}} archiva > commandResult.txt 2>&1 || true"

    String result = readFile('commandResult.txt').trim()
    //String resultEr= readFile('commandResultErr')
    echo "valor exitvalue="+result
    
    String str="Error: No such image or container: archiva"
    if(!(result == str)){
        echo "sutting down archiva"
        sh "docker stop archiva"
        sh "docker rm archiva"
    }
  
    sh "docker inspect -f {{.State.Running}} dbMongo > commandResultM.txt 2>&1 || true"

    String resultM = readFile('commandResultM.txt').trim()
    echo "valor exitvalue="+resultM
    
    String strM="Error: No such image or container: dbMongo"
    if(!(resultM == strM)){
        echo "sutting down dbMongo"
        sh "docker stop dbMongo"
        sh "docker rm dbMongo"
    }
   // Mark the code checkout 'stage'....
   stage 'downloadingTheCode'
   

   // Get some code from a GitHub repository
   git url: 'https://github.com/EugenioFidel/kurento-java.git'
   
   // Mark the code checkout 'stage'....
   stage 'StartingContainers'

   //Starting dbMongo container
   
   sh "docker run --name dbMongo -d mongo:latest"
   sh "docker run -ti --name archiva -d eugeniojavier/archiva:master"
   
   
   env.PWD="${env.PWD}/jobs/${env.JOB_NAME}/workspace"
   echo "${env.PWD}"
   //getting archiva IP
   sh "docker inspect -f {{.NetworkSettings.IPAddress}} archiva > ipArchiva.txt 2>&1 || true"
   String rst = readFile('ipArchiva.txt').trim()
   echo "Archiva IP="+rst
   env.ARCHIVA_IP=rst
   sh "env"
   sh "sed -i 's/ARCHIVA_IP/${env.ARCHIVA_IP}/g' config.json"
   sh "rm ipArchiva.txt"
   
   stage "DockerTest"
   sh "docker run --rm -v ${env.PWD}:/usr/src/kurento-java -v /home/eu/TFG/kurentoXML/kurento-settings-tfg.xml:/opt/kurento-settings-tfg.xml -w /usr/src/kurento-java --name kurento --link dbMongo:dbMongo maven:3.3-jdk-8 mvn --settings /opt/kurento-settings-tfg.xml clean test -Pdefault -U -Dhttp.port=8787 -Djava.security.egd=file:/dev/./urandom -Drepository.mongodb.urlConn=\"mongodb://dbMongo\""
   
   stage 'DockerBuild'
   sh "docker run --rm -v ${env.PWD}:/usr/src/kurento-java -v /home/eu/TFG/kurentoXML/kurento-settings-tfg.xml:/opt/kurento-settings-tfg.xml -w /usr/src/kurento-java -t --link archiva:archiva --link dbMongo:dbMongo maven:3.3-jdk-8 mvn --settings /opt/kurento-settings-tfg.xml clean package org.apache.maven.plugins:maven-deploy-plugin:2.8:deploy -Pdeploy -Dmaven.test.skip=true -Dmaven.wagon.http.ssl.insecure=true -Dmaven.wagon.http.ssl.allowall=true -e -DaltDeploymentRepository=kurento-snapshots::default::http://archiva:8080/repository/snapshots/"
   sh "docker stop dbMongo"
   sh "docker rm dbMongo"
   
   stage 'VChecking'

   sh "docker run --rm -v ${env.PWD}:/config/ -w /config/ -e CONFIGJSON='config.json' eugeniojavier/vcheckerapp:latest || true"
   sh "echo ${env.ARCHIVA_IP}"
   sh "sed -i 's/${env.ARCHIVA_IP}/ARCHIVA_IP/g' config.json"
}
